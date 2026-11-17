package org.icij.datashare.model;

import java.util.List;

/** One entity id a project holds under two types. Rebuilding the entities index refuses the whole
 *  project over such an id, so the write that would create it is refused instead. */
public class EntityTypeClash extends IllegalStateException {
    public final String projectId;
    public final List<String> entityIds;

    public EntityTypeClash(String projectId, List<String> entityIds) {
        super("project '%s' would hold these entity ids under two types: %s".formatted(projectId, entityIds));
        this.projectId = projectId;
        this.entityIds = List.copyOf(entityIds);
    }
}
