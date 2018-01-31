package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import org.icij.datashare.Entity;
import org.icij.datashare.text.hashing.HasherException;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Optional;


/**
 * DataShare Source File Path
 *
 * id = {@link org.icij.datashare.Entity#HASHER}({@code path})
 *
 * Created by julien on 2/3/17.
 */
@IndexType("SourcePath")
public class SourcePath implements Entity, DataSerializable {
    private static final long serialVersionUID = 1587956432685458L;

    /**
     * Instantiate a new {@code SourcePath} from given path which must point to regular, existing, readable file
     *
     * @param path      the file path from which to createList document
     * @return an Optional of {@code Document} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<SourcePath> create(Path path) {
        try {
            return Optional.of( new SourcePath(path) );
        } catch (IllegalStateException | HasherException e) {
            LOGGER.error("Failed to create document", e);
            return Optional.empty();
        }
    }

    // Source file Path
    private Path path;

    // Path as of date
    private Date asOf;

    @IndexId
    @JsonIgnore
    private String hash;


    private SourcePath() {};

    private SourcePath(Path path) throws HasherException, IllegalArgumentException {
        if ( ! Files.exists(path))
            throw new IllegalArgumentException("File " + path + " does not exist.");
        if ( ! Files.isRegularFile(path))
            throw new IllegalArgumentException("File " + path + " is not a regular file.");
        if ( ! Files.isReadable(path))
            throw new IllegalArgumentException("File " + path + " is not readable.");
        this.path = path;
        this.hash = HASHER.hash(getPath().toString());
        if (this.hash.isEmpty())
            throw new HasherException("Failed to hash content of " + this.path);
        this.asOf = new Date();
    }


    public Path getPath() {
        return path;
    }

    @Override
    public String getId() {
        return hash;
    }

    public Date getAsOf() {
        return asOf;
    }

    @Override
    public String toString() {
        return getPath() + "(" + getId() + ")";
    }


    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeUTF(path.toString());
        out.writeObject(asOf);
        out.writeUTF(hash);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        path = Paths.get(in.readUTF());
        asOf = in.readObject();
        hash = in.readUTF();
    }

}
