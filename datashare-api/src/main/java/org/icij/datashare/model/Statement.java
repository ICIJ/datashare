package org.icij.datashare.model;

import org.icij.datashare.text.Hasher;

import java.util.Objects;

public record Statement(String id, String model, String entityId, String entityType,
                        String property, String value, Provenance provenance) {

    public Statement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
    }

    public record Provenance(String documentId, String sheet, long rowNumber, String column) {
        public Provenance {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(column, "column");
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

    // NUL-separated rather than a printable delimiter: a cell value can hold any printable
    // character, so only a value containing a literal NUL could forge a collision.
    private static String id(String model, String entityId, String entityType,
                             String property, String value, Provenance provenance) {
        return Hasher.SHA_384.hash(String.join("\u0000", model, entityId, entityType, property, value,
                provenance.documentId(),
                provenance.sheet() == null ? "" : provenance.sheet(),
                String.valueOf(provenance.rowNumber()),
                provenance.column()));
    }
}
