package org.icij.datashare.model.ftm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.UnreadableModelResource;
import tech.followthemoney.model.Edge;
import tech.followthemoney.model.Model;
import tech.followthemoney.model.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The FollowTheMoney model, as the official {@code tech.followthemoney:followthemoney} library reads
 * the ontology it bundles. The library resolves inherited properties but exposes neither the
 * ontology version nor which schemata are abstract, so both are read from that same bundled JSON.
 * FollowTheMoney is MIT-licensed and its jar ships no notice, so datashare ships it as the
 * {@code META-INF/licenses/followthemoney-LICENSE} resource.
 */
public class FtmTargetModel implements TargetModel {
    private static final String RESOURCE = "/defaultModel.json";
    private static final String LIBRARY = "/META-INF/maven/tech.followthemoney/followthemoney/pom.properties";
    private static final ObjectReader READER = JsonObjectMapper.getMapper().readerFor(FtmEntity.class)
                                                               .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                                                     DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String version;
    private final Map<String, EntityType> types;

    public FtmTargetModel() {
        JsonNode root = ontology();
        JsonNode schemata = present(root, "schemata");
        this.version = present(root, "version").asText();
        // The ontology sits at a generic root path, so another jar on the classpath could shadow it.
        String library = libraryVersion();
        if (!version.equals(library)) {
            throw new UnreadableModelResource(RESOURCE,
                                              "version '" + version + "' does not match followthemoney " + library);
        }
        try {
            Model model = Model.fromJson(JsonObjectMapper.getMapper(), root);
            this.types = model.getSchemata().values().stream().collect(Collectors.collectingAndThen(
                    Collectors.toMap(Schema::getName, schema -> type(schema, schemata), (a, b) -> a, TreeMap::new),
                    Collections::unmodifiableMap));
        } catch (RuntimeException e) {
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
    public Map<String, EntityType> types() {
        return types;
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
            read = READER.readValue(json);
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
        if (type(read.schema()).isEmpty()) {
            throw new IllegalArgumentException("unknown schema '" + read.schema() + "' in FtM JSON");
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

    private static JsonNode ontology() {
        try (InputStream stream = Model.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new UnreadableModelResource(RESOURCE);
            }
            return JsonObjectMapper.getMapper().readTree(stream);
        } catch (IOException e) {
            throw new UnreadableModelResource(RESOURCE, e);
        }
    }

    private static String libraryVersion() {
        try (InputStream stream = Model.class.getResourceAsStream(LIBRARY)) {
            if (stream == null) {
                throw new UnreadableModelResource(LIBRARY);
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UnreadableModelResource(LIBRARY, e);
        }
    }

    static EntityType type(Schema schema, JsonNode schemata) {
        boolean isAbstract = schemata.path(schema.getName()).path("abstract").asBoolean(false);
        return new EntityType(schema.getName(), schema.getLabel(), isAbstract, ancestors(schema), properties(schema),
                              required(schema), names(schema.getFeaturedProperties()),
                              names(schema.getCaptionProperties()),
                              schema.getEdge().map(FtmTargetModel::edge).orElse(null));
    }

    private static Set<String> ancestors(Schema schema) {
        return schema.getSchemata().stream().map(Schema::getName).collect(Collectors.collectingAndThen(
                Collectors.toCollection(TreeSet::new), Collections::unmodifiableSet));
    }

    private static Map<String, Property> properties(Schema schema) {
        return schema.getProperties().stream().collect(Collectors.collectingAndThen(
                Collectors.toMap(tech.followthemoney.model.Property::getName, FtmTargetModel::property, (a, b) -> a,
                                 TreeMap::new), Collections::unmodifiableMap));
    }

    private static Property property(tech.followthemoney.model.Property property) {
        return new Property(property.getLabel(), property.getRange().map(Schema::getName).orElse(null),
                            property.isStub());
    }

    // FtM keeps required per schema, but datashare also enforces what the ancestors require. The type's
    // own requirements come first so a violation list still reads in the order the schema states them.
    private static Set<String> required(Schema schema) {
        Set<String> required = new LinkedHashSet<>(names(schema.getRequiredProperties()));
        schema.getSchemata().stream().sorted(Comparator.comparing(Schema::getName))
              .forEach(ancestor -> required.addAll(names(ancestor.getRequiredProperties())));
        return Collections.unmodifiableSet(required);
    }

    private static List<String> names(List<tech.followthemoney.model.Property> properties) {
        return properties.stream().filter(Objects::nonNull).map(tech.followthemoney.model.Property::getName).toList();
    }

    private static EntityType.Edge edge(Edge edge) {
        return new EntityType.Edge(edge.getSourceProperty().getName(), edge.getTargetProperty().getName());
    }

    // Read through here rather than with get(), so a missing field fails as UnreadableModelResource
    // naming it instead of as a bare NPE.
    private static JsonNode present(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new UnreadableModelResource(RESOURCE, "missing '" + field + "'");
        }
        return value;
    }

    @JsonIgnoreProperties({"caption", "datasets", "referents", "first_seen", "last_seen", "last_change", "target"})
    record FtmEntity(String id, String schema, Map<String, List<String>> properties) {}
}
