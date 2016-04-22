package org.icij.datashare.text;

import java.util.Locale;

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

    ALL          ("ALL"),
    NONE         ("NONE");

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

    public static NamedEntityCategory parse(final String entity) throws IllegalArgumentException {
        if (entity == null || entity.isEmpty()) {
            return NONE;
        }
        try {
            return valueOf(entity.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            for (NamedEntityCategory ne : NamedEntityCategory.values()) {
                if (entity.equalsIgnoreCase(ne.getAbbreviation())) {
                    return ne;
                }
            }
            throw new IllegalArgumentException(String.format("\"%s\" is not a valid entity type", entity));
        }
    }
}
