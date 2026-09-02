package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.icij.datashare.Entity;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An entity rebuilt from the statement store, in the shape the "&lt;project&gt;.entities" index holds:
 *  the property keys are the namespaced wire form ("ftm_birthDate") the statements were stored under,
 *  and the model's type is {@code entityType}, since the indexer stamps every document's {@code type}
 *  field with its kind ("StructuredEntity"). */
@IndexType("StructuredEntity")
public record StructuredEntity(@IndexId String entityId, String model, String entityType, Set<String> modelVersions,
                              Set<String> documentIds, Map<String, List<String>> properties) implements Entity {
    /** Not the colon {@link org.icij.datashare.model.Statement#qualifiedProperty()} stores: a colon is
     *  the field/value delimiter of elasticsearch's query_string, so "properties.ftm:name:Jane" is a
     *  parse error and every client would have to escape the separator to reach a single property. */
    private static final String NAMESPACE_SEPARATOR = "_";

    // ModelEntity's keys are bare ("birthDate"): JooqStatementRepository.toRow strips the model
    // prefix a statement's property is stored under. The namespace goes back on here, at the index
    // boundary, rather than in ModelEntity, which TargetModel.validate and ExtractionMapping.probe
    // consume on the bare form.
    public static StructuredEntity from(ModelEntity entity) {
        Map<String, List<String>> namespaced = new LinkedHashMap<>();
        entity.properties().forEach((property, values) ->
                namespaced.put(entity.model() + NAMESPACE_SEPARATOR + property, values));
        return new StructuredEntity(entity.id(), entity.model(), entity.type(), entity.modelVersions(),
                entity.documentIds(), namespaced);
    }

    /** The document id, which the entity id alone cannot be: the statement store emits one entity per
     *  (entity id, model) pair, so two models describing the same entity would overwrite each other.
     *  Hidden from the source like {@link NamedEntity#getId()}: it already lives in the _id. */
    @Override
    @JsonIgnore
    public String getId() {
        return model + NAMESPACE_SEPARATOR + entityId;
    }
}
