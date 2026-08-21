package org.icij.datashare.model;

import java.util.Map;
import java.util.Set;

public record EntityType(String name, boolean isAbstract, Set<String> ancestors,
                         Map<String, Property> properties, Set<String> required, Edge edge) {

    public record Edge(String source, String target, boolean directed) { }
}
