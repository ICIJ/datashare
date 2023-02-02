package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Project;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.lang.String.format;
import static java.time.ZonedDateTime.from;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.Collections.unmodifiableList;
import static java.util.Optional.ofNullable;

public class BatchDownload {
    public static final String ZIP_FORMAT = "archive_%s_%s.zip";

    public final String uuid;
    public final List<Project> projects;
    public final Path filename;
    public final String query;
    public final String uri;
    public final User user;
    public final boolean encrypted;
    public volatile long zipSize;

    @JsonIgnore
    private final JsonNode jsonNode;

    public BatchDownload(final List<Project> projects, User user, String query, String uri) {
        this(projects, user, query,  uri, Paths.get(System.getProperty("java.io.tmpdir")),false);
    }

    public BatchDownload(final List<Project> projects, User user, String query) {
        this(projects, user, query, null, Paths.get(System.getProperty("java.io.tmpdir")), false);
    }

    public BatchDownload(final List<Project> projects, User user, String query, String uri, Path downloadDir, boolean isEncrypted)  {
        this(UUID.randomUUID().toString(), projects, downloadDir.resolve(createFilename(user)), query, uri, user, isEncrypted, 0);
    }

    public BatchDownload(final List<Project> projects, User user, String query, Path downloadDir, boolean isEncrypted)  {
        this(UUID.randomUUID().toString(), projects, downloadDir.resolve(createFilename(user)), query, null, user, isEncrypted, 0);
    }

    @JsonCreator
    private BatchDownload(@JsonProperty("uuid") final String uuid,
                          @JsonProperty("projects") final List<Project> projects,
                          @JsonProperty("filename") Path filename,
                          @JsonProperty("query") String query,
                          @JsonProperty("uri") String uri,
                          @JsonProperty("user") User user,
                          @JsonProperty("encrypted") boolean encrypted,
                          @JsonProperty("zipSize") long zipSize) {
        this.uuid = uuid;
        this.projects = unmodifiableList(ofNullable(projects).orElse(new ArrayList<>()));
        this.user = user;
        this.query = ofNullable(query).orElseThrow(() -> new IllegalArgumentException("query cannot be null or empty"));
        this.uri = uri;
        this.filename = filename;
        this.encrypted = encrypted;
        this.zipSize = zipSize;
        if (isJsonQuery()) {
            try {
                jsonNode = JsonObjectMapper.MAPPER.readTree(query.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) { // should be a JsonParseException
                throw new IllegalArgumentException(e);
            }
        } else {
            jsonNode = null;
        }
    }

    public static Path createFilename(User user) {
        User nonNullUser = ofNullable(user).orElseThrow(() -> new IllegalArgumentException("user cannot be null or empty"));
        // Fix : double dot char cannot be contained in a file name on Windows
        String strTime = ISO_DATE_TIME.format(from(DatashareTime.getInstance().now().toInstant().atZone(ZoneId.of("GMT")))).replace(":", "_");
        return Paths.get(format(ZIP_FORMAT, nonNullUser.getId(), strTime));
    }

    public static BatchDownload nullObject() {
        return new BatchDownload(null, null, Paths.get("/dev/null"), "", null, User.nullUser(),false, 0L);
    }

    /**
     * useful because zipSize cannot be known by object constructor method
     * (before the zip file actually exists on disk)
     *
     * see org.icij.datashare.tasks.BatchDownloadRunner
     * @param zipSize
     */
    public void setZipSize(long zipSize) {
        this.zipSize = zipSize;
    }

    public boolean getExists() {
        return Files.exists(this.filename);
    }

    @JsonIgnore
    public boolean isJsonQuery() {
        return query.trim().startsWith("{") && query.trim().endsWith("}");
    }

    public JsonNode queryAsJson() {
        return ofNullable(jsonNode).orElseThrow(() -> new IllegalStateException("cannot get JSON node from query string"));
    }

    @Override
    public String toString() { return "BatchDownload{filename=" + filename + '}'; }
    @Override
    public int hashCode() {
        return Objects.hash(filename, query);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchDownload that = (BatchDownload) o;
        return Objects.equals(filename, that.filename) && Objects.equals(query, that.query);
    }
}
