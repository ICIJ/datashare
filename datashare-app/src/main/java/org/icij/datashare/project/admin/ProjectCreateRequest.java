package org.icij.datashare.project.admin;

import java.nio.file.Path;

public record ProjectCreateRequest(
        String name,
        String label,
        String description,
        Path sourcePath,
        String allowFromMask,
        String sourceUrl,
        String maintainerName,
        String publisherName,
        String logoUrl,
        boolean createIndex
) {}
