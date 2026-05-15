package org.icij.datashare.project.admin;

import java.nio.file.Path;
import java.util.Date;

/**
 * Result of a create / createIfNotExists call.
 *
 * <p>When {@code noop} is {@code true}, the project already existed and the
 * fields mirror the row already in the database. {@code indexCreated} is
 * always {@code false} in that case (we did not create an index in this call,
 * regardless of whether the existing project has one).
 */
public record ProjectCreated(
        String name,
        String label,
        String description,
        Path sourcePath,
        String allowFromMask,
        String sourceUrl,
        String maintainerName,
        String publisherName,
        String logoUrl,
        Date creationDate,
        Date updateDate,
        boolean indexCreated,
        boolean noop
) {}
