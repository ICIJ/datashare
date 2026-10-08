package org.icij.datashare.project.admin;

/**
 * Outcome of a project delete cascade. {@code casbinDeleted} and {@code inventoryDeleted} cover the
 * access cleanup: the project's Casbin rows, and its name in every user's inventory. Both are false
 * when the step failed or when it was skipped because the database delete did not succeed.
 */
public record ProjectDeleted(String name, boolean dbDeleted, boolean indexDeleted, boolean casbinDeleted, boolean inventoryDeleted, boolean queuesDeleted, boolean reportMapDeleted, boolean artifactsDeleted, boolean noop) {}
