package org.icij.datashare.json;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexRoot;
import org.icij.datashare.text.indexing.IndexType;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.PropertyAccessor.FIELD;


/**
 * JSON - POJO Index mapping functions
 *
 * Expect the following annotations from given (domain entity) objects
 * {@link org.icij.datashare.text.indexing.IndexId @IndexId},
 * {@link org.icij.datashare.text.indexing.IndexType @IndexType},
 * {@link com.fasterxml.jackson.annotation.JsonCreator @JSONCreator},
 * {@link com.fasterxml.jackson.annotation.JsonProperty @JSONProperty}
 *
 * Created by julien on 6/29/16.
 */

public class JsonObjectMapper {

    // JSON - Object mapper
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper TYPE_INCLUSION_MAPPER;

    public static final int MAX_NESTING_DEPTH = 20;

    public static final int MAX_NUMBER_LENGTH = 100;

    public static final int MAX_STRING_LENGTH = 1000000000;

    static {
        synchronized (JsonObjectMapper.class) {
            // Handle Optional and other JDK 8 only features
            MAPPER.registerModule(new Jdk8Module());
            // Avoid annotations on domain entities by
            // using compiled methods' metadata
            MAPPER.registerModule(new ParameterNamesModule());
            //  Making domain entities' private fields visible to Jackson
            MAPPER.setVisibility(FIELD, ANY);
            MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxNumberLength(MAX_NUMBER_LENGTH)
                    .maxStringLength(MAX_STRING_LENGTH).build());
            TYPE_INCLUSION_MAPPER = MAPPER.copy();
            TypeResolverBuilder<?> mapTyper = new ObjectMapper.DefaultTypeResolverBuilder(
                    ObjectMapper.DefaultTyping.NON_FINAL, LaissezFaireSubTypeValidator.instance)
                    .init(JsonTypeInfo.Id.CLASS, null)
                    .typeProperty("@type")
                    .inclusion(JsonTypeInfo.As.PROPERTY);
            TYPE_INCLUSION_MAPPER.setDefaultTyping(mapTyper);
        }
    }

    /**
     * Get JSON representation (as a Map) of an Object instance
     *
     * @param obj the object to convert to JSON
     * @param <T> the concrete type of entity
     * @return JSON representation of {@code obj}
     */
    public static <T extends Entity> Map<String, Object> getJson(T obj) {
        String json;
        try {
            json = MAPPER.writeValueAsString(obj);
            return MAPPER.readValue(json, new TypeReference<HashMap<String, Object>>(){});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T extends Entity> T getObject(String id, String projectId, Map<String, Object> source, Class<T> type) {
        HashMap<String, Object> map;
        if (source == null) {
            map = new HashMap<>() {{
                put("id", id);
                put("projectId", projectId);
            }};
        } else {
            map = new HashMap<>() {{
                putAll(source);
                put("id", id);
                put("projectId", projectId);
            }};
        }
        return getObject(map, type);
    }

    public static <T extends Entity> T getObject(Map<String, Object> source, Class<T> type) {
        try {
            return MAPPER.readValue(MAPPER.writeValueAsString(source), type);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot deserialize object map " + source, e);
        }
    }

    /**
     * Get value of {@link IndexType} annotation from Class
     *
     * @param cls the class holding index type-annotated field
     * @return the index type String
     */
    public static String getType(Class<? extends Entity> cls){
        return cls.getAnnotation(IndexType.class).value();
    }

    /**
     * Get the value of {@link IndexType} annotation
     * from DataShare domain entity Object instance
     *
     * @param obj the object instance from which {@code @IndexType} is extracted
     * @param <T> the concrete type of entity
     * @return the index type String
     */
    public static <T extends Entity> String getType(T obj){
        return getType(obj.getClass());
    }

    /**
     * Get the field value marked with {@link IndexId} from DataShare domain entity object instance
     *
     * @param obj the object holding the index id-annotated field
     * @param <T> the concrete type of entity
     * @return the index id String
     */
    public static <T extends Entity> String getId(T obj) {
        for(Field field : obj.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(IndexId.class)) {
                field.setAccessible(true);
                try {
                    return (String) field.get(obj);
                } catch (IllegalAccessException e) {
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Get the field value marked with {@code @IndexParent} from DataShare domain entity object instance
     *
     * @param obj the object holding the index id-annotated field
     * @param <T> the concrete type of entity
     * @return the parent's hash String
     */
    public static <T extends Entity> String getParent(T obj) {
        for(Field field : obj.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(IndexParent.class)) {
                field.setAccessible(true);
                try {
                    return (String) field.get(obj);
                } catch (IllegalAccessException e) {
                    break;
                }
            }
        }
        return null;
    }

    public static <T extends Entity> String getRoot(T obj) {
        for (Field field : obj.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(IndexRoot.class)) {
                field.setAccessible(true);
                try {
                    return (String) field.get(obj);
                } catch (IllegalAccessException e) {
                    break;
                }
            }
        }
        return null;
    }

    public static JsonNode readTree(byte[] rawJson) throws IOException {
        return MAPPER.readTree(rawJson);
    }

    public static boolean isValidJson(String json) {
        try {
            MAPPER.readTree(json);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public static Map<String, Object> deserialize(String jsonMap) {
        try {
            return new ObjectMapper().readValue(jsonMap, new TypeReference<HashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String serialize(Map<String, Object> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * this should not be called except for initialization (Elasticsearch, Redis, ...)
     * to avoid side effects.
     *
     * prefer using delegating methods from this class.
     * @return ObjectMapper
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    public static JsonFactory getFactory() {
        return MAPPER.getFactory();
    }

    public static <T> T convertValue(Object obj, TypeReference<T> typeReference) {
        return MAPPER.convertValue(obj, typeReference);
    }

    public static void registerSubtypes(NamedType... classesToRegister) {
        TYPE_INCLUSION_MAPPER.registerSubtypes(classesToRegister);
        MAPPER.registerSubtypes(classesToRegister);
        LoggerFactory.getLogger(JsonObjectMapper.class).info("Registered task types : "
                + Arrays.stream(classesToRegister).map(namedType -> namedType.getType().getSimpleName()).toList());
    }

    public static <T> T readValue(String rawJson, TypeReference<T> type) throws IOException {
        return MAPPER.readValue(rawJson, type);
    }

    public static <T> T readValue(String rawJson, Class<T> type) throws IOException {
        return MAPPER.readValue(rawJson, type);
    }

    public static <T> T readValueTyped(byte[] rawJson, Class<T> type) throws IOException {
        return TYPE_INCLUSION_MAPPER.readValue(rawJson, type);
    }

    public static <T> T readValueTyped(String rawJson, TypeReference<T> type) throws IOException {
        return TYPE_INCLUSION_MAPPER.readValue(rawJson, type);
    }

    public static <T> T readValueTyped(String rawJson, Class<T> type) throws IOException {
        return TYPE_INCLUSION_MAPPER.readValue(rawJson, type);
    }

    public static <T> T readValueTyped(String rawJson, JavaType type) throws IOException {
        return TYPE_INCLUSION_MAPPER.readValue(rawJson, type);
    }

    public static String writeValueAsString(Object obj) throws JsonProcessingException {
        return MAPPER.writeValueAsString(obj);
    }

    public static String writeValueAsStringTyped(Object obj) throws JsonProcessingException {
        return TYPE_INCLUSION_MAPPER.writeValueAsString(obj);
    }

    public static byte[] writeValueAsBytesTyped(Object obj) throws JsonProcessingException {
        return TYPE_INCLUSION_MAPPER.writeValueAsBytes(obj);
    }

    public static void writeValueTyped(File file, Object obj) throws IOException {
        TYPE_INCLUSION_MAPPER.writeValue(file, obj);
    }

    public static CollectionType constructCollectionType(Class<? extends Collection> arrayListClass, Class<?> mapClass) {
        return MAPPER.getTypeFactory().constructCollectionType(arrayListClass, mapClass);
    }

    public static JavaType constructParametricType(Class<?> parametrized, Class<?> parameterClass) {
        return TYPE_INCLUSION_MAPPER.getTypeFactory().constructParametricType(parametrized, parameterClass);
    }
}
