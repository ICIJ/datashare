package org.icij.datashare.text;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.NlpPipeline;


/**
 * Created by julien on 4/26/16.
 */
public class Corpus implements Entity {

    private static final Logger LOGGER = LogManager.getLogger(Corpus.class);

    private static final long serialVersionUID = 2568979856231459L;


    private final String name;

    private final String hash;

    private final Path sourcePath;

    private final Path processedPath;


    public Corpus(String corpus, Path source, Path processed) {
        name = corpus;
        hash = HASHER.hash(name);
        sourcePath = source;
        processedPath = processed;
    }


    @Override
    public String getHash() {
        return hash;
    }

    public String getName() {
        return name;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public Path getProcessedPath() {
        return processedPath;
    }

    public boolean writeToIndex(Indexer idxr) {
        return false;
    }

    public boolean readFromIndex(Indexer idxr) { return false; }

    public boolean processDirectory(Path inputDir, List<NlpPipeline> pipelines) {
        return false;
    }

    public boolean processIndex(Indexer idxr, List<NlpPipeline> pipelines) {
        return false;
    }

}
