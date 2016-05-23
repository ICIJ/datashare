package org.icij.datashare.text.processing;

import java.util.Locale;
import java.util.Optional;

/**
 * Created by julien on 4/4/16.
 */
public enum NamedEntityCategory {
    PERSON       ("PERS"),
    ORGANIZATION ("ORG"),
    LOCATION     ("LOC"),
    DATE         ("DATE"),
    MONEY        ("MON"),
    NUMBER       ("NUM"),

    NONE         ("NONE"),
    UNKNOWN      ("UNKNOWN");

    private final String abbreviation;

    NamedEntityCategory(String abbreviation) {
        this.abbreviation = abbreviation;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    @Override
    public String toString() {
        return name().toLowerCase();
    }

    public static Optional<NamedEntityCategory> parse(final String entity) {
        if (    entity == null ||
                entity.isEmpty() ||
                entity.equalsIgnoreCase(NONE.toString()) ||
                entity.equalsIgnoreCase(UNKNOWN.toString()) ||
                entity.trim().equals("0") ||
                entity.trim().equals("O") )
            return Optional.empty();
        try {
            return Optional.of(valueOf(entity.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            for (NamedEntityCategory ne : NamedEntityCategory.values()) {
                if (entity.equalsIgnoreCase(ne.getAbbreviation())) {
                    return Optional.of(ne);
                }
            }
            //throw new IllegalArgumentException(String.format("\"%s\" is not a valid entity type", entity));
            return Optional.empty();
        }
    }
}
