package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;

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
    public final String uri;
    public final User user;
    public final boolean encrypted;
    public final SearchQuery query;

    public BatchDownload(final List<Project> projects, User user, String query, String uri) {
        this(projects, user, query,  uri, Paths.get(System.getProperty("java.io.tmpdir")),false);
    }

    public BatchDownload(final List<Project> projects, User user, String query) {
        this(projects, user, query, null, Paths.get(System.getProperty("java.io.tmpdir")), false);
    }

    public BatchDownload(final List<Project> projects, User user, String query, String uri, Path downloadDir, boolean isEncrypted)  {
        this(UUID.randomUUID().toString(), projects, downloadDir.resolve(createFilename(user)),
                new SearchQuery(ofNullable(query).orElseThrow(() -> new IllegalArgumentException("query cannot be null"))), uri, user, isEncrypted);
    }

    @JsonCreator
    BatchDownload(@JsonProperty("uuid") final String uuid,
                  @JsonProperty("projects") final List<Project> projects,
                  @JsonProperty("filename") Path filename,
                  @JsonProperty("query") SearchQuery query,
                  @JsonProperty("uri") String uri,
                  @JsonProperty("user") User user,
                  @JsonProperty("encrypted") boolean encrypted) {
        this.query = ofNullable(query).orElseThrow(() -> new IllegalArgumentException("query cannot be null or empty"));
        if ( query.isNull() || (query.isJsonQuery() && query.asJson() == null)) {
            throw new IllegalArgumentException("invalid query: " + query);
        }
        this.uuid = uuid;
        this.projects = unmodifiableList(ofNullable(projects).orElse(new ArrayList<>()));
        this.user = user;
        this.uri = uri;
        this.filename = filename;
        this.encrypted = encrypted;
    }

    public static Path createFilename(User user) {
        User nonNullUser = ofNullable(user).orElseThrow(() -> new IllegalArgumentException("user cannot be null or empty"));
        // Fix : double dot char cannot be contained in a file name on Windows
        String strTime = ISO_DATE_TIME.format(from(DatashareTime.getInstance().now().toInstant().atZone(ZoneId.of("GMT")))).replace(":", "_");
        return Paths.get(format(ZIP_FORMAT, nonNullUser.getId(), strTime));
    }

    public boolean getExists() {
        return Files.exists(this.filename);
    }

    @JsonIgnore
    public boolean isJsonQuery() {
        return query.isJsonQuery();
    }

    public JsonNode queryAsJson() {
        return query.asJson();
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
