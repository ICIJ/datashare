package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.PathDeserializer;
import org.icij.datashare.text.PathSerializer;
import org.icij.datashare.text.Project;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Objects;

import static java.lang.String.format;
import static java.time.ZonedDateTime.from;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.Optional.ofNullable;

public class BatchDownload {
    public static final String ZIP_FORMAT = "archive_%s_%s_%s.zip";

    public final Project project;
    @JsonSerialize(using = PathSerializer.class)
    @JsonDeserialize(using = PathDeserializer.class)
    public final Path filename;
    public final String query;
    public final User user;

    @JsonIgnore
    private final JsonNode jsonNode;

    public BatchDownload(final Project project, User user, String query) {
        this(project, user, query, Paths.get(System.getProperty("java.io.tmpdir")));
    }

    public BatchDownload(final Project project, User user, String query, Path downloadDir)  {
        this.project = ofNullable(project).orElseThrow(() -> new IllegalArgumentException("project cannot be null or empty"));
        this.query = ofNullable(query).orElseThrow(() -> new IllegalArgumentException("query cannot be null or empty"));
        this.user = ofNullable(user).orElseThrow(() -> new IllegalArgumentException("user cannot be null or empty"));
        this.filename = downloadDir.resolve(createFilename(project, user));
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

    public static Path createFilename(Project project, User user) {
        String strTime = ISO_DATE_TIME.format(from(DatashareTime.getInstance().now().toInstant().atZone(ZoneId.of("GMT"))));
        return Paths.get(format(ZIP_FORMAT, project.name, user.getId(), strTime));
    }

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
