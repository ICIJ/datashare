package org.icij.datashare.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record EntityType(String name, String label, boolean isAbstract, Set<String> ancestors, Map<String, Property> properties, Set<String> required, List<String> featured, List<String> caption, Edge edge) {
    public record Edge(String source, String target) {}
}
