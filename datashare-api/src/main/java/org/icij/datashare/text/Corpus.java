package org.icij.datashare.text;

import org.icij.datashare.Entity;

import java.nio.file.Path;
import java.nio.file.Paths;


public class Corpus implements Entity {
    private static final long serialVersionUID = 2568979856231459L;

    private final String name;
    private final Path sourcePath;

    public Corpus(String corpusName) {
        this(corpusName, Paths.get("/vault").resolve(corpusName));
    }

    public Corpus(String corpusName, Path source) {
        name = corpusName;
        sourcePath = source;
    }

    @Override
    public String getId() {
        return name;
    }
    public String getName() {
        return name;
    }
    public Path getSourcePath() {
        return sourcePath;
    }
}
