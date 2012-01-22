/*
 * Copyright (c) 2007-2011 by The Broad Institute of MIT and Harvard.  All Rights Reserved.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 *
 * THE SOFTWARE IS PROVIDED "AS IS." THE BROAD AND MIT MAKE NO REPRESENTATIONS OR
 * WARRANTES OF ANY KIND CONCERNING THE SOFTWARE, EXPRESS OR IMPLIED, INCLUDING,
 * WITHOUT LIMITATION, WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE, NONINFRINGEMENT, OR THE ABSENCE OF LATENT OR OTHER DEFECTS, WHETHER
 * OR NOT DISCOVERABLE.  IN NO EVENT SHALL THE BROAD OR MIT, OR THEIR RESPECTIVE
 * TRUSTEES, DIRECTORS, OFFICERS, EMPLOYEES, AND AFFILIATES BE LIABLE FOR ANY DAMAGES
 * OF ANY KIND, INCLUDING, WITHOUT LIMITATION, INCIDENTAL OR CONSEQUENTIAL DAMAGES,
 * ECONOMIC DAMAGES OR INJURY TO PROPERTY AND LOST PROFITS, REGARDLESS OF WHETHER
 * THE BROAD OR MIT SHALL BE ADVISED, SHALL HAVE OTHER REASON TO KNOW, OR IN FACT
 * SHALL KNOW OF THE POSSIBILITY OF THE FOREGOING.
 */

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.broad.igv.feature.genome;

import org.apache.log4j.Logger;
import org.broad.igv.Globals;

import org.broad.igv.feature.MaximumContigGenomeException;
import org.broad.igv.ui.util.ProgressMonitor;
import org.broad.igv.util.*;
import org.broad.tribble.readers.AsciiLineReader;
import org.broad.igv.util.ZipArchiveWrapper.ZipIterator;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * /**
 *
 * @author jrobinso
 */
public class GenomeImporter {
    public static final int MAX_CONTIGS = 1000000;

    static Logger log = Logger.getLogger(GenomeImporter.class);
    public static final Pattern SEQUENCE_NAME_SPLITTER = Pattern.compile("\\s+");


    /**
     * Create a zip containing all the information and data required to load a
     * genome. All file/directory validation is assume to have been done by validation
     * outside of this method.
     *
     * @param archiveOutputLocation
     * @param genomeFileName
     * @param genomeId                       Id of the genome.
     * @param genomeDisplayName              The genome name that is user-friendly.
     * @param sequenceLocation       The location of sequence data.
     * @param sequenceInputFile
     * @param refFlatFile                    RefFlat file.
     * @param cytobandFile                   Cytoband file.
     * @param sequenceOutputLocationOverride
     * @param monitor
     * @return The newly created genome archive file.
     */
    public File createGenomeArchive(File archiveOutputLocation,
                                    String genomeFileName,
                                    String genomeId,
                                    String genomeDisplayName,
                                    String sequenceLocation,
                                    File sequenceInputFile,
                                    File refFlatFile,
                                    File cytobandFile,
                                    File chrAliasFile,
                                    String sequenceOutputLocationOverride,
                                    ProgressMonitor monitor) throws IOException {

        if ((archiveOutputLocation == null) || (genomeFileName == null) || (genomeId == null) || (genomeDisplayName == null)) {

            log.error("Invalid input for genome creation: ");
            log.error("\tGenome Output Location=" + archiveOutputLocation);
            log.error("\tGenome filename=" + genomeFileName);
            log.error("\tGenome Id=" + genomeId);
            log.error("\tGenome Name" + genomeDisplayName);
            return null;
        }


        // Create a tmp directory for genome files
        File tmpdir = new File(Globals.getGenomeCacheDirectory(), genomeFileName + "_tmp");
        if(tmpdir.exists()) {
            tmpdir.delete();
        }
        tmpdir.mkdir();

        boolean autoGeneratedCytobandFile = (cytobandFile == null) ? true : false;
        boolean singleFasta = false;
        boolean chromsSorted = false;
        boolean altererdChrFilenames = false;

        File archive = null;
        FileWriter propertyFileWriter = null;
        try {

            // If we have a FASTA file we need to use the passed sequence
            // location as a directory to place the generated sequences.
            if (sequenceInputFile != null) {
                LinkedHashMap<String, Integer> chromSizes = new LinkedHashMap();

                File sequenceOutputFolder = new File(archiveOutputLocation, sequenceLocation);
                if (!sequenceOutputFolder.exists()) {
                    sequenceOutputFolder.mkdir();
                }


                if (sequenceInputFile.isDirectory()) {
                    // FASTA directory
                    // Create all sequence files
                    List<File> files = getSequenceFiles(sequenceInputFile);
                    int progressIncrement = ((files.size() > 0) ? (50 / files.size()) : 50);
                    for (File file : files) {
                        if (!file.getName().startsWith(".")) {
                            altererdChrFilenames = createSequenceFiles(file, sequenceOutputFolder, genomeId, chromSizes, monitor);
                            if (monitor != null) {
                                monitor.fireProgressChange(progressIncrement);
                            }
                        }
                    }
                } else if (sequenceInputFile.getName().toLowerCase().endsWith(Globals.ZIP_EXTENSION)) {
                    // Zip of fastas
                    ZipArchiveWrapper zip = new ZipArchiveWrapper(sequenceInputFile);
                    ZipIterator iterator = null;
                    ZipInputStream inputStream = null;
                    try {

                        int entryCount = zip.getEntryCount();
                        int progressIncrement = ((entryCount > 0) ? 50 / entryCount : 50);

                        // Create all sequences
                        iterator = zip.iterator();
                        inputStream = iterator.getZipInputStream();
                        while (iterator.hasNext()) {

                            iterator.next();    // Move to next entry

                            // Create Sequence Data
                            altererdChrFilenames = createSequenceFiles(inputStream, sequenceOutputFolder, genomeId, chromSizes, monitor);

                            if (monitor != null) {
                                monitor.fireProgressChange(progressIncrement);
                            }
                        }
                        iterator.close();
                    } finally {
                        if (iterator != null) {
                            iterator.close();
                        }
                        if (inputStream != null) {
                            inputStream.close();
                        }
                    }
                } else {
                    // Single fasta
                    altererdChrFilenames = createSequenceFiles(sequenceInputFile, sequenceOutputFolder, genomeId, chromSizes, monitor);
                    singleFasta = true;
                }

                // Create Cytoband file
                if (autoGeneratedCytobandFile) {
                    // Construct a cytoband file
                    String cytobandFileName = genomeId + "_cytoband.txt";
                    cytobandFile = new File(tmpdir, cytobandFileName);
                    cytobandFile.deleteOnExit();
                    generateCytobandFile(chromSizes, cytobandFile, singleFasta);
                    chromsSorted = true;
                }
            }

            // Create Property File for genome archive
            if (sequenceOutputLocationOverride != null && sequenceOutputLocationOverride.length() > 0) {
                sequenceLocation = sequenceOutputLocationOverride;
            }
            File propertyFile = createGenomePropertyFile(genomeId, genomeDisplayName,
                    sequenceLocation, refFlatFile, cytobandFile,
                    chrAliasFile, chromsSorted, altererdChrFilenames, tmpdir);
            propertyFile.deleteOnExit();
            archive = new File(archiveOutputLocation, genomeFileName);
            File[] inputFiles = {refFlatFile, cytobandFile, propertyFile, chrAliasFile};
            Utilities.createZipFile(archive, inputFiles);

            propertyFile.delete();
            cytobandFile.delete();
            tmpdir.delete();

        } finally {
            if (propertyFileWriter != null) {
                try {
                    propertyFileWriter.close();
                } catch (IOException ex) {
                    log.error("Failed to close genome archive: +" + archive.getAbsolutePath(), ex);
                }
            }

            if (autoGeneratedCytobandFile) {
                if ((cytobandFile != null) && cytobandFile.exists()) {
                    cytobandFile.deleteOnExit();
                }
            }
        }
        return archive;
    }

    private List<File> getSequenceFiles(File sequenceDir) {
        ArrayList<File> files = new ArrayList();
        for (File f : sequenceDir.listFiles()) {
            if (f.isDirectory()) {
                files.addAll(getSequenceFiles(f));
            } else {
                files.add(f);
            }
        }

        return files;
    }


    private void generateCytobandFile(Map<String, Integer> chromSizes, File cytobandFile, boolean singleFasta) throws IOException {


        PrintWriter cytobandFileWriter = null;
        try {
            if (!cytobandFile.exists()) {
                cytobandFile.createNewFile();
            }
            cytobandFileWriter = new PrintWriter(new FileWriter(cytobandFile, true));

            List<String> chrNames = new ArrayList(chromSizes.keySet());
            if (!singleFasta) {
                Collections.sort(chrNames, new GenomeImpl.ChromosomeComparator());
            }

            // Generate a single cytoband per chromosome.  Length == chromosome length
            for (String chrName : chrNames) {
                int chrLength = chromSizes.get(chrName).intValue();
                cytobandFileWriter.println(chrName + "\t0\t" + chrLength);
            }
        } finally {
            if (cytobandFileWriter != null) {
                cytobandFileWriter.close();
            }
        }
    }


    /**
     * This method creates the property.txt file that is stored in each
     * .genome file. This is not the user-defined genome property file
     * created by storeUserDefinedGenomeListToFile(...)
     *
     * @param genomeId                 The genome's id.
     * @param genomeDisplayName
     * @param relativeSequenceLocation
     * @param refFlatFile
     * @param cytobandFile
     * @return
     */
    public File createGenomePropertyFile(String genomeId,
                                         String genomeDisplayName,
                                         String relativeSequenceLocation,
                                         File refFlatFile,
                                         File cytobandFile,
                                         File chrAliasFile,
                                         boolean chromsSorted,
                                         boolean alteredChrFilenames,
                                         File tmpdir) throws IOException {

        PrintWriter propertyFileWriter = null;
        try {

            File propertyFile = new File(tmpdir, "property.txt");
            propertyFile.createNewFile();

            // Add the new property file to the archive
            propertyFileWriter = new PrintWriter(new FileWriter(propertyFile));

            if (alteredChrFilenames) {
                propertyFileWriter.println("filenamesAltered=true");
            }

            propertyFileWriter.println("ordered=" + String.valueOf(chromsSorted));
            if (genomeId != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_ID_KEY + "=" + genomeId);
            }
            if (genomeDisplayName != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_NAME_KEY + "=" + genomeDisplayName);
            }
            if (cytobandFile != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_CYTOBAND_FILE_KEY + "=" + cytobandFile.getName());
            }
            if (refFlatFile != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_GENE_FILE_KEY + "=" + refFlatFile.getName());
            }
            if (chrAliasFile != null) {
                propertyFileWriter.println(Globals.GENOME_CHR_ALIAS_FILE_KEY + "=" + chrAliasFile.getName());
            }
            if (relativeSequenceLocation != null) {
                if (!HttpUtils.getInstance().isURL(relativeSequenceLocation)) {
                    relativeSequenceLocation = relativeSequenceLocation.replace('\\', '/');
                }
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_SEQUENCE_FILE_LOCATION_KEY + "=" + relativeSequenceLocation);
            }
            return propertyFile;

        } finally {
            if (propertyFileWriter != null) {
                propertyFileWriter.close();

            }
        }

    }

    /**
     * Creates chromosome sequence files.
     *
     * @param sequenceInputFile    A FASTA file.
     * @param genomeSequenceFolder The output folder for chromosome sequence
     *                             files.
     * @param genomeId             The genome Id.
     * @param monitor
     * @throws IOException
     */
    public boolean createSequenceFiles(File sequenceInputFile,
                                       File genomeSequenceFolder,
                                       String genomeId,
                                       LinkedHashMap<String, Integer> chromSizes,
                                       ProgressMonitor monitor)
            throws IOException {

        if (sequenceInputFile == null) {
            log.error("Invalid input for sequence creation: ");
            log.error("\tSequence Filename =" + sequenceInputFile);
            log.error("\tSequence Location =" + genomeSequenceFolder);
            log.error("\tGenome Id =" + genomeId);
            return false;
        }

        InputStream inputStream = null;
        try {
            if (sequenceInputFile.getName().toLowerCase().endsWith(Globals.FASTA_GZIP_FILE_EXTENSION)) {

                // A single FASTA file is in a .gz file
                inputStream = new GZIPInputStream(new FileInputStream(sequenceInputFile));
            } else {

                // A single FASTA file not in any type of compressed file
                inputStream = new FileInputStream(sequenceInputFile);
            }
            return createSequenceFiles(inputStream, genomeSequenceFolder, genomeId, chromSizes, monitor);
        }
        finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    /**
     * Creates chromosome sequence files.
     *
     * @param sequenceInputStream  Input stream for a FASTA file.
     * @param genomeSequenceFolder The output folder for chromosome sequence files.
     * @param genomeId             The genome Id.
     *                             stream should be automatically close before exiting this method.
     * @param monitor
     * @throws IOException
     */
    public boolean createSequenceFiles(InputStream sequenceInputStream,
                                       File genomeSequenceFolder,
                                       String genomeId,
                                       LinkedHashMap<String, Integer> chromSizes,
                                       ProgressMonitor monitor)
            throws IOException {

        boolean alteredChrFilenames = false;

        if (sequenceInputStream == null) {
            log.error("Invalid input for sequence creation: ");
            log.error("\tInput Stream =" + sequenceInputStream);
            log.error("\tSequence Location =" + genomeSequenceFolder);
            log.error("\tGenome Id =" + genomeId);
            return alteredChrFilenames;
        }

        int contigCounter = 0;
        BufferedWriter chromosomeFileWriter = null;
        AsciiLineReader dataReader = null;
        try {
            if (!genomeSequenceFolder.exists()) {
                genomeSequenceFolder.mkdir();
            }

            dataReader = new AsciiLineReader(sequenceInputStream);

            String fastaDataLine = null;
            String chr = null;
            int chrSize = 0;
            while ((fastaDataLine = dataReader.readLine()) != null) {

                // If we reached the number of allowed contigs throw an error
                if (contigCounter > MAX_CONTIGS) {
                    throw new MaximumContigGenomeException(
                            "Maximum number of contigs exceeded (" + MAX_CONTIGS + ")");
                }

                fastaDataLine = fastaDataLine.trim();

                // If a new chromosome name
                if (fastaDataLine.startsWith(">")) {

                    if (chr != null) {
                        chromSizes.put(chr, chrSize);
                    }

                    // Count contigs processed
                    ++contigCounter;

                    // Find the first word break.  According the the spec the id of the sequence
                    // is the first "word",  the remaining part of the line is a comment.

                    String [] tokens = SEQUENCE_NAME_SPLITTER.split(fastaDataLine, 2);
                    chr = tokens[0].substring(1);
                    chrSize = 0;
                    String chrFileName = chr + ".txt";
                    String legalFileName = FileUtils.legalFileName(chrFileName);
                    if (!chrFileName.equals(legalFileName)) {
                        alteredChrFilenames = true;
                    }

                    File chromosomeSequenceFile = new File(genomeSequenceFolder, legalFileName);
                    chromosomeSequenceFile.createNewFile();


                    if (chromosomeFileWriter != null) {
                        chromosomeFileWriter.close();
                        chromosomeFileWriter = null;
                    }
                    chromosomeFileWriter = new BufferedWriter(new FileWriter(chromosomeSequenceFile));
                } else if(chromosomeFileWriter != null) {
                    chrSize += fastaDataLine.length();
                    chromosomeFileWriter.write(fastaDataLine.toUpperCase());
                }
            }

            // Last chr
            if (chr != null) {
                chromSizes.put(chr, chrSize);
            }
            return alteredChrFilenames;

        } finally {
            if (chromosomeFileWriter != null) {
                chromosomeFileWriter.close();
            }
            if (dataReader != null) {
                dataReader.close();
            }
        }

    }


}
