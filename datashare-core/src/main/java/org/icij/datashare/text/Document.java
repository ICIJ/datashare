package org.icij.datashare.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import org.icij.datashare.text.hashing.Hasher;
import org.icij.datashare.text.extraction.DocumentParser;

import static org.icij.datashare.text.hashing.Hasher.SHA_384;


/**
 * Created by julien on 4/26/16.
 */
public class Document {

    private static final Logger LOGGER = Logger.getLogger(Document.class.getName());

    private static final Hasher HASHER = SHA_384;


    public static Optional<Document> create(Path path)  {
        if (path == null) {
            LOGGER.log(WARNING, "Failed to create Document. Path is undefined");
            return Optional.empty();
        }
        if ( ! Files.exists(path)) {
            LOGGER.log(WARNING, "Failed to create Document. " + path + " does not exist.");
            return Optional.empty();
        }
        if ( ! Files.isRegularFile(path)) {
            LOGGER.log(WARNING, "Failed to create Document. " + path + " is not a regular file.");
            return Optional.empty();
        }
        if ( ! Files.isReadable(path)) {
            LOGGER.log(WARNING, "Failed to create Document. " + path + " is not readable.");
            return Optional.empty();
        }

        return Optional.of(new Document(path));
    }


    private final Path path;

    private final Date asOf;


    private String content;

    private String hash;

    private String type;

    private int length;

    private Language language;

    private Map<String, String> metadata;

    private boolean read = false;


    private Document(Path p) {
        path = p;
        asOf = new Date();
    }


    public Path getPath() {
        return path;
    }

    public String getName() { return path.getName(path.getNameCount()-1).toString(); }

    public Date getAsOf() { return asOf; }


    public Optional<String> getContent() { return Optional.ofNullable(content); }

    public Optional<String> getHash() { return Optional.ofNullable(hash); }

    public Optional<Integer> getLength() { return Optional.ofNullable(length); }

    public Optional<Language> getLanguage() { return Optional.ofNullable(language); }

    public Optional<String> getType() { return Optional.ofNullable(type); }

    public Optional<Map<String, String>> getMetadata() { return Optional.ofNullable(metadata); }


    public boolean read() {
        return read(true);
    }

    public boolean read(boolean enableOcr) {
        if (read)
            return true;

        Optional<DocumentParser> optParser = DocumentParser.create();
        if ( ! optParser.isPresent()) {
            LOGGER.log(SEVERE, "Failed to create suitable DocumentParser " + path);
            return false;
        }
        DocumentParser parser = optParser.get();

        if ( ! enableOcr)
            parser.disableOcr();

        Optional<String> optCont = parser.parse(path);
        if ( ! optCont.isPresent()) {
            LOGGER.log(SEVERE, "Empty content of " + path);
            return false;
        }
        content = optCont.get();

        hash = HASHER.hash(content);
        if (hash.isEmpty()) {
            LOGGER.log(SEVERE, "Failed to hash content of " + path);
            return false;
        }

        language = parser.getLanguage().orElse(Language.UNKNOWN);

        Optional<Map<String, String>> optMetadata = parser.getMetadata();
        if (optMetadata.isPresent()) {
            metadata = optMetadata.get();
            type = parser.getType().orElse("");
            length = parser.getLength().orElse(0);
        }
        read = true;

        return true;
    }

}
