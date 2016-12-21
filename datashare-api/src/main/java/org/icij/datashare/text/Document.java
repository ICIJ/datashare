package org.icij.datashare.text;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;

import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.extraction.FileParserException;
import org.icij.datashare.text.hashing.HasherException;
import org.icij.datashare.text.nlp.Annotation;


/**
 * DataShare document.
 *
 * Created by julien on 4/26/16.
 */
@IndexType("Document")
public class Document implements Entity {

    private static final Logger LOGGER = LogManager.getLogger(Document.class);

    private static final long serialVersionUID = 5913568429773112L;

    public static long getSerialVersionUID() { return serialVersionUID; }


    /**
     * Instantiate a new {@code Document} from given path which must point to regular, existing, readable file
     *
     * @param path      the file path from which to createList document
     * @param content   the text content
     * @param language  the detected content language
     * @param metadata  the rest of extracted metadata
     * @param mimeType  the detected mime type
     * @return an Optional of {@code Document} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<Document> create(Path path,
                                            String content,
                                            Language language,
                                            Charset encoding,
                                            String mimeType,
                                            Map<String, String> metadata,
                                            FileParser.Type parserType) {
        try {
            return Optional.of( new Document(path, content, language, encoding, mimeType, parserType, metadata) );

        } catch (IllegalStateException | FileParserException | HasherException e) {
            LOGGER.error("Failed to createList document", e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate a new {@code Document} from given parser and path
     *
     * @param path    the file path from which to createList document
     * @param parser  the used parser
     * @return an Optional of immutable {@code Document} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<Document> create(Path path, FileParser parser)  {
        try {
            return parser.parse(path) ;

        } catch (NullPointerException | IllegalStateException e) {
            LOGGER.error("Failed to create document", e);
            return Optional.empty();
        }
    }

    public static Optional<Document> create(Path path, FileParser.Type parserType, Properties properties) {
        Optional<FileParser> parser = FileParser.create(parserType, properties);
        if ( ! parser.isPresent()) {
            LOGGER.error("Failed to create suitable file parser");
            return Optional.empty();
        }
        return create(path, parser.get());
    }

    public static Optional<Document> create(Path path, Properties properties) {
        return create(path, FileParser.DEFAULT_TYPE, properties);
    }

    public static Optional<Document> create(Path path) {
        return create(path, new Properties());
    }


    // Source file path
    private final Path path;

    // Path and content as of date
    private final Date asOf;

    // Content string
    private final String content;

    // Content hash code
    @IndexId
    @JsonIgnore
    private final String hash;

    // Content length
    private final int length;

    // Mime-type
    private final String type;

    // Detected content language
    private final Language language;

    // Detected encoding
    private final Charset encoding;

    // Extracted metadata
    private final Map<String, String> metadata;

    // Type of file parser which has extracted content
    private final FileParser.Type parser;


    @JsonCreator
    private Document(Path            path,
                     String          content,
                     Language        language,
                     Charset         encoding,
                     String          mimeType,
                     FileParser.Type parserType,
                     Map<String, String> metadata)
            throws NullPointerException, IllegalArgumentException, FileParserException, HasherException {
        if ( ! Files.exists(path)) {
            throw new IllegalArgumentException("File " + path + " does not exist.");
        }
        if ( ! Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File " + path + " is not a regular file.");
        }
        if ( ! Files.isReadable(path)) {
            throw new IllegalArgumentException("File " + path + " is not readable.");
        }
        this.path = path;
        this.content = content;
        this.hash = HASHER.hash(content);
        if (this.hash.isEmpty()) {
            throw new HasherException("Failed to hash content of " + this.path);
        }
        this.asOf     = new Date();
        this.length   = content.length();
        this.language = language;
        this.type     = mimeType;
        this.encoding = encoding;
        this.metadata = metadata;
        this.parser   = parserType;
    }

//    private Document(@JsonProperty("path") Path p, FileParser fileParser)
//            throws NullPointerException, IllegalArgumentException, FileParserException, HasherException {
//
//        if ( ! Files.exists(p)) {
//            throw new IllegalArgumentException("File " + p + " does not exist.");
//        }
//        if ( ! Files.isRegularFile(p)) {
//            throw new IllegalArgumentException("File " + p + " is not a regular file.");
//        }
//        if ( ! Files.isReadable(p)) {
//            throw new IllegalArgumentException("File " + p + " is not readable.");
//        }
//        if (fileParser == null) {
//            throw new NullPointerException("FileParser is undefined");
//        }
//        path = p;
//        Optional<String> contOpt = fileParser.parse(path);
//        if ( ! contOpt.isPresent()) {
//            throw new FileParserException("Failed to get content " + path);
//        }
//        content  = contOpt.get();
//        hash     = HASHER.hash(content);
//        if (hash.isEmpty()) {
//            throw new HasherException("Failed to hash content of " + path);
//        }
//        asOf     = new Date();
//        language = fileParser.getLanguage().orElse(Language.UNKNOWN);
//        type     = fileParser.getMimeType().orElse("");
//        length   = fileParser.getLength().orElse(0);
//        metadata = fileParser.getMetadata().orElseGet(HashMap::new);
//        parser   = fileParser.getType();
//    }

    @Override
    public String getHash() { return hash; }

    public String getContent() { return content; }

    public Path getPath() {
        return path;
    }

    public Date getAsOf() { return asOf; }

    public FileParser.Type getParser() { return parser; }

    public Optional<Charset> getEncoding() { return Optional.ofNullable(encoding); }

    public Optional<Integer> getLength() { return Optional.ofNullable(length); }

    public Optional<Language> getLanguage() { return Optional.ofNullable(language); }

    public Optional<String> getType() { return Optional.ofNullable(type); }

    public Optional<Map<String, String>> getMetadata() { return Optional.ofNullable(metadata); }

    @JsonIgnore
    public String getName() { return path.getName(path.getNameCount()-1).toString(); }

    public List<NamedEntity> getNamedEntities(Annotation annotation) {
        return NamedEntity.allFrom(this, annotation);
    }

    @Override
    public String toString() {
        return getName() + "(" + getHash() + ")";
    }

}
