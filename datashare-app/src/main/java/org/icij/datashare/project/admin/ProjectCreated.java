package org.icij.datashare.project.admin;

import java.nio.file.Path;

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
        boolean indexCreated,
        boolean noop
) {}
