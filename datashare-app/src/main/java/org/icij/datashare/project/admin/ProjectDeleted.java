package org.icij.datashare.project.admin;

public record ProjectDeleted(
        String name,
        boolean dbDeleted,
        boolean indexDeleted,
        boolean queuesDeleted,
        boolean reportMapDeleted,
        boolean artifactsDeleted,
        boolean noop
) {}
