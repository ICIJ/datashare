package org.icij.datashare.text;

import org.icij.datashare.Entity;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An entity rebuilt from the statement store, in the shape the "&lt;project&gt;.entities" index holds:
 *  the property keys are the namespaced wire form ("ftm:birthDate") the statements were stored under. */
@IndexType("ExtractedEntity")
public record ExtractedEntity(@IndexId String id, String model, Set<String> modelVersions, Set<String> types,
                              Set<String> documentIds, Map<String, List<String>> properties) implements Entity {

    // ModelEntity's keys are bare ("birthDate"): JooqStatementRepository.toRow strips the model
    // prefix a statement's property is stored under. The namespace goes back on here, at the index
    // boundary, rather than in ModelEntity, which TargetModel.validate and ExtractionMapping.probe
    // consume on the bare form.
    public static ExtractedEntity from(ModelEntity entity) {
        Map<String, List<String>> namespaced = new LinkedHashMap<>();
        entity.properties().forEach((property, values) -> namespaced.put(entity.model() + ":" + property, values));
        return new ExtractedEntity(entity.id(), entity.model(), entity.modelVersions(), entity.types(),
                entity.documentIds(), namespaced);
    }

    @Override
    public String getId() {
        return id;
    }
}
