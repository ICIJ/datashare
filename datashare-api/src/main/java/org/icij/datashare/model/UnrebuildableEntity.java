package org.icij.datashare.model;

import java.util.Collection;
import java.util.TreeSet;

/** One entity's statements contradict each other, so no entity can be folded out of them. Distinct
 *  from the other refusals {@link ModelEntity#from} makes, which say the statements were grouped
 *  wrongly: this one says the data is bad, and a rebuild may skip the entity and carry on, where a
 *  grouping fault must stop it. */
public class UnrebuildableEntity extends IllegalArgumentException {
    public final Collection<String> types;

    public UnrebuildableEntity(Collection<String> types) {
        super("statements give the entity %d types: %s".formatted(types.size(), new TreeSet<>(types)));
        this.types = new TreeSet<>(types);
    }
}
