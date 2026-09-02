package org.icij.datashare.model.ftm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.UnreadableModelResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The FollowTheMoney model, read from a bundled copy of its prebuilt ontology. The
 * {@code org.icij:ftm.java} artifact on the classpath is a build-time code generator and carries no
 * model data, so the file is vendored: version 4.10.2, retrieved 2026-08-21 from
 * https://raw.githubusercontent.com/opensanctions/followthemoney/b9418ecd32bd60dd09c261134464860a0082ffb7/js/src/defaultModel.json
 * with sha256 d1733d7963b4a662e8162529dfc8a1eb0dd86d035425853fbc67c26b63d99992. That path carries
 * no {@code 4.10.2} tag, so the commit sha is the only stable pin. FollowTheMoney is MIT-licensed
 * and its notice ships beside the ontology, as the {@code ftm/LICENSE} resource.
 */
public class FtmTargetModel implements TargetModel {
    private static final String RESOURCE = "ftm/defaultModel-4.10.2.json";

    private final String version;
    private final Map<String, EntityType> types;

    public FtmTargetModel() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new UnreadableModelResource(RESOURCE);
            }
            JsonNode root = JsonObjectMapper.getMapper().readTree(stream);
            this.version = present(root, "version").asText();
            this.types = types(present(root, "schemata"));
        } catch (IOException e) {
            throw new UnreadableModelResource(RESOURCE, e);
        }
    }

    @Override
    public String name() {
        return "ftm";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Optional<EntityType> type(String name) {
        return Optional.ofNullable(types.get(name));
    }

    @Override
    public String serialize(ModelEntity entity) {
        if (type(entity.type()).isEmpty()) {
            throw new IllegalArgumentException(
                    "type '" + entity.type() + "' is no schema of the FtM model, so it cannot be written as FtM JSON");
        }
        try {
            return JsonObjectMapper.writeValueAsString(new FtmEntity(entity.id(), entity.type(), entity.properties()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot write entity '" + entity.id() + "' as FtM JSON", e);
        }
    }

    @Override
    public ModelEntity parse(String json) {
        FtmEntity read;
        try {
            read = JsonObjectMapper.readValue(json, FtmEntity.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read FtM JSON", e);
        }
        if (read == null) {
            throw new IllegalArgumentException("no entity in FtM JSON");
        }
        if (read.id() == null) {
            throw new IllegalArgumentException("missing 'id' in FtM JSON");
        }
        if (read.schema() == null) {
            throw new IllegalArgumentException("missing 'schema' in FtM JSON");
        }
        Map<String, List<String>> properties = read.properties() == null ? Map.of() : read.properties();
        properties.forEach((property, values) -> {
            if (values == null) {
                throw new IllegalArgumentException("property '" + property + "' has no values in FtM JSON");
            }
            if (values.contains(null)) {
                throw new IllegalArgumentException("property '" + property + "' has a null value in FtM JSON");
            }
        });
        return new ModelEntity(name(), read.id(), read.schema(), Set.of(), Set.of(), properties);
    }

    private static Map<String, EntityType> types(JsonNode schemata) {
        Map<String, Map<String, Property>> properties = new HashMap<>();
        Map<String, Set<String>> required = new HashMap<>();
        schemata.properties().forEach(schema -> {
            properties.put(schema.getKey(), properties(schema.getValue().path("properties")));
            required.put(schema.getKey(), strings(schema.getValue().path("required")));
        });
        Map<String, EntityType> types = new HashMap<>();
        schemata.properties().forEach(schema ->
                types.put(schema.getKey(), type(schema.getKey(), schema.getValue(), properties, required)));
        return Map.copyOf(types);
    }

    // The prebuilt ontology resolves neither properties nor required transitively, so both are merged
    // over the ancestor closure here: 28 of the 69 concrete schemata require a property they inherit
    // without redeclaring it. The type's own requirements come first so a violation list still reads
    // in the order the schema states them.
    private static EntityType type(String name, JsonNode schema, Map<String, Map<String, Property>> declared,
                                   Map<String, Set<String>> declaredRequired) {
        Set<String> ancestors = strings(present(schema, "schemata"));
        Map<String, Property> properties = new HashMap<>();
        Set<String> required = new LinkedHashSet<>(declaredRequired.getOrDefault(name, Set.of()));
        // Two ancestors can declare the same property with different qnames: last one by name wins.
        new TreeSet<>(ancestors).forEach(ancestor -> {
            properties.putAll(declared.getOrDefault(ancestor, Map.of()));
            required.addAll(declaredRequired.getOrDefault(ancestor, Set.of()));
        });
        return new EntityType(name, schema.path("abstract").asBoolean(false), ancestors,
                Map.copyOf(properties), Collections.unmodifiableSet(required), edge(schema.path("edge")));
    }

    private static Map<String, Property> properties(JsonNode node) {
        Map<String, Property> declared = new HashMap<>();
        node.properties().forEach(property -> declared.put(property.getKey(), property(property.getValue())));
        return declared;
    }

    private static Property property(JsonNode property) {
        return new Property(present(property, "qname").asText(), property.path("range").asText(null),
                property.path("stub").asBoolean(false));
    }

    private static EntityType.Edge edge(JsonNode edge) {
        return edge.isMissingNode() || edge.isNull() ? null
                : new EntityType.Edge(present(edge, "source").asText(), present(edge, "target").asText(),
                        edge.path("directed").asBoolean(false));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Collections.unmodifiableSet(values);
    }

    // Every field this parser needs is read through here rather than with get(), so a bundle whose
    // shape moved fails as UnreadableModelResource naming the field instead of as a bare NPE.
    private static JsonNode present(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new UnreadableModelResource(RESOURCE, "missing '" + field + "'");
        }
        return value;
    }

    record FtmEntity(String id, String schema, Map<String, List<String>> properties) { }
}
