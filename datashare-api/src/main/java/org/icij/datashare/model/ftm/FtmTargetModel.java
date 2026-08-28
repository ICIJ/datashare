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
import java.util.ArrayList;
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
            this.version = root.get("version").asText();
            this.types = types(root.get("schemata"));
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
        String schema = mostSpecific(entity.types()).orElseThrow(() -> new IllegalArgumentException(
                "types " + new TreeSet<>(entity.types()) + " have no common schema in the FtM model"));
        try {
            return JsonObjectMapper.writeValueAsString(new FtmEntity(entity.id(), schema, entity.properties()));
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
        return new ModelEntity(read.id(), Set.of(read.schema()), properties);
    }

    @Override
    public List<Violation> validate(ModelEntity entity) {
        List<Violation> violations = new ArrayList<>(TargetModel.super.validate(entity));
        if (entity.types().size() > 1 && mostSpecific(entity.types()).isEmpty()) {
            violations.add(new Violation("types " + new TreeSet<>(entity.types())
                    + " have no common schema, so the entity cannot be written as FtM JSON"));
        }
        return violations;
    }

    private Optional<String> mostSpecific(Set<String> types) {
        return types.stream()
                .filter(candidate -> type(candidate)
                        .map(found -> found.ancestors().containsAll(types))
                        .orElse(false))
                .findFirst();
    }

    private static Map<String, EntityType> types(JsonNode schemata) {
        Map<String, Map<String, Property>> declared = new HashMap<>();
        schemata.fields().forEachRemaining(schema ->
                declared.put(schema.getKey(), properties(schema.getValue().get("properties"))));
        Map<String, EntityType> types = new HashMap<>();
        schemata.fields().forEachRemaining(schema ->
                types.put(schema.getKey(), type(schema.getKey(), schema.getValue(), declared)));
        return Map.copyOf(types);
    }

    private static EntityType type(String name, JsonNode schema, Map<String, Map<String, Property>> declared) {
        Set<String> ancestors = strings(schema.get("schemata"));
        Map<String, Property> properties = new HashMap<>();
        // Two ancestors can declare the same property with different qnames: last one by name wins.
        new TreeSet<>(ancestors).forEach(ancestor -> properties.putAll(declared.getOrDefault(ancestor, Map.of())));
        return new EntityType(name, schema.path("abstract").asBoolean(false), ancestors,
                Map.copyOf(properties), strings(schema.path("required")), edge(schema.path("edge")));
    }

    private static Map<String, Property> properties(JsonNode properties) {
        Map<String, Property> declared = new HashMap<>();
        properties.fields().forEachRemaining(property ->
                declared.put(property.getKey(), property(property.getValue())));
        return declared;
    }

    private static Property property(JsonNode property) {
        return new Property(property.get("name").asText(), property.get("qname").asText(),
                property.get("type").asText(), text(property, "range"),
                property.path("stub").asBoolean(false));
    }

    private static EntityType.Edge edge(JsonNode edge) {
        return edge.isMissingNode() ? null : new EntityType.Edge(edge.get("source").asText(),
                edge.get("target").asText(), edge.get("directed").asBoolean(false));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Collections.unmodifiableSet(values);
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    record FtmEntity(String id, String schema, Map<String, List<String>> properties) { }
}
