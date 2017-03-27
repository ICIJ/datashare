package org.icij.datashare.json;

import java.util.*;
import static java.util.Arrays.asList;
import java.lang.reflect.Field;
import java.io.IOException;
import java.text.SimpleDateFormat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.PropertyAccessor.FIELD;

import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.indexing.IndexParent;


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
    public static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        // Handle Optional and other JDK 8 only features
        MAPPER.registerModule(new Jdk8Module());
        // Access Optional values
        MAPPER.addMixIn(Optional.class, OptionalMixin.class);
        // Date format
        MAPPER.setDateFormat( new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ") );
        // Avoid annotations on domain entities by
        // using compiled methods' metadata
        MAPPER.registerModule(new ParameterNamesModule());
        //  Making domain entities' private fields visible to Jackson
        MAPPER.setVisibility(FIELD, ANY);
    }

    public static final TypeReference< Map<String, Object> > MAP_TYPEREF = new TypeReference< Map<String, Object> >(){};


    /**
     * Get JSON representation (as a Map) of an Object instance
     *
     * @param obj the object to convert to JSON
     * @param <T> the concrete type of entity
     * @return JSON representation of {@code obj}
     */
    public static <T extends Entity> Map<String, Object> getJson(T obj) {
        // Convert Object to JSON string
        String json = null;
        try {
            json = MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
        // Map TypeReference
        TypeReference typeRef = new TypeReference<Map<String, Object>>(){};
        // convert JSON string to Map
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reifies domain entity Object instance of type {@code T} from fields given as JSON String
     *
     * @param id     the document's index unique identifier
     * @param source the source JSON String representation
     * @param type   the document's index type
     * @param <T>    the type of constructed object
     * @return a new object instance of type T filled from arguments
     */
    public static <T extends Entity> T getObject(String id, String source, Class<T> type) {
        try {
            T obj = MAPPER.readValue(source, type);
            setId(obj, type, id);
            return obj;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reifies domain entity object instance of type {@code T} from class and fields given as JSON Map
     *
     * @param id     the document's index unique identifier
     * @param source the source JSON Map representation
     * @param type   the document's index type
     * @param <T>    the concrete type of entity
     * @return a new object instance of type T filled from arguments
     *
     */
    public static <T extends Entity> T getObject(String id, Map<String, Object> source, Class<T> type) {
        try {
            T obj = MAPPER.readValue(MAPPER.writeValueAsString(source), type);
            setId(obj, type, id);
            return obj;
        } catch (IOException e) {
            return null;
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
    public static <T extends Entity> Optional<String> getParent(T obj) {
        for(Field field : obj.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(IndexParent.class)) {
                field.setAccessible(true);
                try {
                    return Optional.of( (String) field.get(obj) );
                } catch (IllegalAccessException e) {
                    break;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Set the field value annotated with {@link IndexId} from DataShare domain entity object instance
     *
     * @param obj   the object holding the index id-annotated field
     * @param type  the type class
     * @param value the index id-annotated field value
     * @param <T>   the type of returned entity object
     */
    private static <T extends Entity> void setId(T obj, Class<T> type, String value) {
        for(Field field : getAllFields(type)) {
            if (field.isAnnotationPresent(IndexId.class)) {
                field.setAccessible(true);
                try {
                    field.set(obj, value);
                } catch (IllegalAccessException e) {
                    break;
                }
                break;
            }
        }
    }

    /**
     * Get all declared fields
     *
     * @param cls the scrutinized class
     * @return the list of declared fields
     */
    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            fields.addAll(asList(c.getDeclaredFields()));
        }
        return fields;
    }

}
