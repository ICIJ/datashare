package org.icij.datashare.project.admin;

import java.nio.file.Path;
import java.util.Date;

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
