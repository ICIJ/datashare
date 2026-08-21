package org.icij.datashare.model;

import java.util.Collection;
import java.util.TreeSet;

public class UnknownTargetModel extends IllegalArgumentException {
    public final String name;

    public UnknownTargetModel(String name, Collection<String> known) {
        super("unknown data model '%s', known models: %s".formatted(name, new TreeSet<>(known)));
        this.name = name;
    }
}
