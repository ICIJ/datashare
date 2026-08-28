package org.icij.datashare.model;

import org.icij.datashare.text.Hasher;

import java.util.Objects;

public record Statement(String id, String model, String entityId, String entityType,
                        String property, String value, Provenance provenance) {
    /** An entity id ends up in the (prj_id, entity_id) index, whose entries Postgres caps at 2704
     *  bytes and SQLite does not cap at all. Bounding it here is what keeps the two dialects from
     *  disagreeing on which mapping key a project accepts. */
    public static final int MAX_ENTITY_ID_LENGTH = 512;

    public Statement {
        Objects.requireNonNull(id, "id");
        model = component(model, "model");
        entityId = component(entityId, "entityId");
        if (entityId.length() > MAX_ENTITY_ID_LENGTH) {
            throw new IllegalArgumentException("'entityId' is longer than " + MAX_ENTITY_ID_LENGTH
                    + " characters: " + entityId.length());
        }
        entityType = component(entityType, "entityType");
        property = component(property, "property");
        value = component(value, "value");
        Objects.requireNonNull(provenance, "provenance");
    }

    public record Provenance(String documentId, String sheet, long rowNumber, String column) {
        public Provenance {
            documentId = component(documentId, "documentId");
            sheet = sheet == null ? "" : component(sheet, "sheet");
            column = component(column, "column");
        }
    }

    /** Builds a statement for the write path, rejecting a model no {@link TargetModelRegistry} knows
     *  before any of it reaches the store. Leniency is a read-only promise: the canonical constructor
     *  takes any model name, so a row written under a model since retired still reads back, but
     *  writing that row again fails, since the store stamps every row with the model's version. */
    public static Statement of(String model, String entityId, String entityType,
                               String property, String value, Provenance provenance) {
        Objects.requireNonNull(model, "model");
        TargetModelRegistry.get(model);
        return new Statement(id(model, entityId, entityType, property, value, provenance),
                model, entityId, entityType, property, value, provenance);
    }

    public String qualifiedProperty() {
        return model + ":" + property;
    }

    private static String component(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("'" + field + "' contains a NUL character");
        }
        return value;
    }

    // NUL-separated rather than a printable delimiter: a cell value can hold any printable
    // character, and every joined component is rejected at construction if it holds a NUL, so no
    // input can forge a collision.
    private static String id(String model, String entityId, String entityType,
                             String property, String value, Provenance provenance) {
        return Hasher.SHA_384.hash(String.join("\u0000", model, entityId, entityType, property, value,
                provenance.documentId(),
                provenance.sheet(),
                String.valueOf(provenance.rowNumber()),
                provenance.column()));
    }
}
