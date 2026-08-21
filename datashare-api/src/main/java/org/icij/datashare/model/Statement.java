package org.icij.datashare.model;

import org.icij.datashare.text.Hasher;

import java.util.Objects;

public record Statement(String id, String model, String entityId, String entityType,
                        String property, String value, Provenance provenance) {

    public Statement {
        Objects.requireNonNull(id, "id");
        model = component(model, "model");
        entityId = component(entityId, "entityId");
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

    public static Statement of(String model, String entityId, String entityType,
                               String property, String value, Provenance provenance) {
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
