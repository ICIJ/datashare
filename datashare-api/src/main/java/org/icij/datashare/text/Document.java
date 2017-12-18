package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import org.icij.datashare.Entity;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.hashing.HasherException;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.Annotation;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;


/**
 * DataShare document
 *
 * id = {@link org.icij.datashare.Entity#HASHER}({@code content})
 *
 * Created by julien on 4/26/16.
 */
@IndexType("Document")
public final class Document implements Entity, DataSerializable {
    private static final long serialVersionUID = 5913568429773112L;


    /**
     * Instantiate a new {@code Document} from given path which must point to regular, existing, readable file
     *
     * @param path         the file path from which to createList document
     * @param content      the text content
     * @param language     the detected content language
     * @param metadata     the rest of extracted metadata
     * @param contentType  the detected mime content contentType
     * @return an Optional of {@code Document} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<Document> create(Path path,
                                            String content,
                                            Language language,
                                            Charset encoding,
                                            String contentType,
                                            Map<String, String> metadata,
                                            FileParser.Type parserType) {
        try {
            return Optional.of( new Document(path, content, language, encoding, contentType, parserType, metadata) );
        } catch (IllegalStateException | HasherException e) {
            LOGGER.error("Failed to create document", e);
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
    private Path path;

    // Path and content as of date
    private Date asOf;

    // Content string
    private String content;

    // Content hash code
    @IndexId
    @JsonIgnore
    private String hash;

    // Content length
    private int length;

    // Mime-contentType
    private String contentType;

    // Detected content language
    private Language language;

    // Detected encoding
    private Charset encoding;

    // Extracted metadata
    private Map<String, String> metadata;

    // Type of file parser which has extracted content
    private FileParser.Type parser;


    private Document() {}

    @JsonCreator
    private Document(Path                path,
                     String              content,
                     Language            language,
                     Charset             encoding,
                     String              mimeType,
                     FileParser.Type     parserType,
                     Map<String, String> metadata)
            throws HasherException {
        this.path = path;
        this.content = content;
        this.hash = HASHER.hash(content);
        if (this.hash.isEmpty()) {
            throw new HasherException("Failed to hash content of " + this.path);
        }
        this.asOf     = new Date();
        this.length   = content.length();
        this.language = language;
        this.contentType = mimeType;
        this.encoding = encoding;
        this.metadata = metadata;
        this.parser   = parserType;
    }


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

    public Optional<String> getContentType() { return Optional.ofNullable(contentType); }

    public Optional<Map<String, String>> getMetadata() { return Optional.ofNullable(metadata); }

    @JsonIgnore
    public String getName() { return path.getName(path.getNameCount()-1).toString(); }

    public List<NamedEntity> namedEntities(Annotation annotation) {
        return NamedEntity.allFrom(this, annotation);
    }

    @Override
    public String toString() {
        return getName() + "(" + getHash() + ")";
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeUTF(path.toString());
        out.writeObject(asOf);
        out.writeUTF(encoding.toString());
        out.writeByteArray(content.getBytes());
        out.writeUTF(hash);
        out.writeInt(length);
        out.writeObject(contentType);
        out.writeObject(language);
        out.writeObject(metadata);
        out.writeObject(parser);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        path        = Paths.get(in.readUTF());
        asOf        = in.readObject();
        encoding    = Charset.forName(in.readUTF());
        content     = new String(in.readByteArray(), encoding);
        hash        = in.readUTF();
        length      = in.readInt();
        contentType = in.readObject();
        language    = in.readObject();
        metadata    = in.readObject();
        parser      = in.readObject();
    }

}
