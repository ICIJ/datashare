package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.Entity;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING;
import static com.fasterxml.jackson.annotation.JsonCreator.Mode.PROPERTIES;


public class Project extends ProjectProxy {
    private static final long serialVersionUID = 2568979856231459L;
    public final Path sourcePath;
    @JsonIgnore
    public final String allowFromMask;
    @JsonIgnore
    private final Pattern pattern;
    public final String label;
    public final String description;
    public final String publisherName;
    public final String maintainerName;
    public final String logoUrl;
    public final String sourceUrl;
    public final Date creationDate;
    public final Date updateDate;


    @JsonCreator(mode = DELEGATING)
    public Project(String name) {
        this(name, Paths.get("/vault").resolve(name), "*.*.*.*");
    }
    @JsonCreator(mode = PROPERTIES)
    public Project( @JsonProperty("name") String name,
                    @JsonProperty("sourcePath") Path sourcePath) {
        this(name, sourcePath, "*.*.*.*");
    }

    public Project(String name, Path sourcePath, String allowFromMask) {
        this(name,
            name,
            null,
            sourcePath,
            null,
            null,
            null,
            null,
            allowFromMask,
            null,
            null
        );
    }

    public Project(String name,
                   String label,
                   Path sourcePath,
                   String sourceUrl,
                   String maintainerName,
                   String publisherName,
                   String logoUrl,
                   String allowFromMask,
                   Date creationDate,
                   Date updateDate) {
        this(name,
            label,
            null,
            sourcePath,
            sourceUrl,
            maintainerName,
            publisherName,
            logoUrl,
            allowFromMask,
            creationDate,
            updateDate
        );
    }

    public Project(String name,
                   String label,
                   String description,
                   Path sourcePath,
                   String sourceUrl,
                   String maintainerName,
                   String publisherName,
                   String logoUrl,
                   String allowFromMask,
                   Date creationDate,
                   Date updateDate) {
        super(name);
        this.label = label;
        this.description = description;
        this.sourcePath = sourcePath;
        this.sourceUrl = sourceUrl;
        this.maintainerName = maintainerName;
        this.publisherName = publisherName;
        this.logoUrl = logoUrl;
        this.allowFromMask = allowFromMask;
        this.creationDate = creationDate;
        this.updateDate = updateDate;
        this.pattern = Pattern.compile(allowFromMask.
                replace(".", "\\.").
                replace("*", "\\d{1,3}"));
    }

    public Project(String name, String allowFromMask) {
        this(name, Paths.get("/vault").resolve(name), allowFromMask);
    }

    public boolean isAllowed(final InetSocketAddress socketAddress) {
        return pattern.matcher(socketAddress.getAddress().getHostAddress()).matches();
    }

    public static boolean isAllowed(Project project, InetSocketAddress socketAddress) {
        return project == null || project.isAllowed(socketAddress);
    }

    public String getName() {
        return name;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public String getMaintainerName() {
        return maintainerName;
    }

    public String getAllowFromMask() {
        return allowFromMask;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public static Project project(final String projectName) {
        return new Project(projectName);
    }

    @Override
    public String toString() { return "Project{name='" + name + '\'' + '}';}
}
