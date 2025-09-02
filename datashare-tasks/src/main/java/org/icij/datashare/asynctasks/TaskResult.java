package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedClassResolver;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.json.JsonObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;


@JsonSerialize(using = TaskResult.TaskResultSerializer.class)
@JsonDeserialize(using = TaskResult.TaskResultDeserializer.class)
public record TaskResult<V extends Serializable> (
        @JsonSubTypes({
                @JsonSubTypes.Type(value = UriResult.class),
                @JsonSubTypes.Type(value = Long.class)
        })
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
        V value) implements Serializable {
    public TaskResult(V value) {
        this.value = value;
    }

    public static class TaskResultSerializer extends JsonSerializer<TaskResult<?>> {

        public TaskResultSerializer() {
            super();
        }

        @Override
        public void serialize(TaskResult<?> taskResult, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            ObjectMapper mapper = (ObjectMapper) gen.getCodec();
            Object value = taskResult.value();

            gen.writeStartObject();

            if (value != null) {
                JsonNode valueNode = mapper.valueToTree(value);
                Iterator<Map.Entry<String, JsonNode>> fields = valueNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    gen.writeFieldName(entry.getKey());
                    gen.writeTree(entry.getValue());
                }
            } else {
                gen.writeNullField("value");
            }

            gen.writeEndObject();
        }
    }

    public static class TaskResultDeserializer extends JsonDeserializer<TaskResult<?>> {

        public TaskResultDeserializer() {
            super();
        }

        @Override
        public TaskResult<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            JsonNode node = p.getCodec().readTree(p);
            String type = node.get("@type").asText();

            Field field;
            try {
                field = TaskResult.class.getDeclaredField("value");
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
            Class<?> baseType = field.getType();

            MapperConfig<?> config = mapper.getDeserializationConfig();
            AnnotatedClass ac = AnnotatedClassResolver.resolve(config, mapper.constructType(baseType), config);

            List<NamedType> subtypes = mapper.getSubtypeResolver()
                    .collectAndResolveSubtypesByClass(config, ac).stream().toList();

            Class<?> clazz = subtypes.stream().map(NamedType::getType)
                    .filter(subtypeClass -> subtypeClass.getSimpleName().equals(type))
                    .findFirst().orElseThrow(() -> new NoSuchElementException("No subtype found for name: " + type));

            return new TaskResult<>(mapper.readerFor(clazz).readValue(node));
        }
    }
}
