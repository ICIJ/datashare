package org.icij.datashare.model;

import org.icij.datashare.model.ftm.FtmJsonExporter;
import java.io.OutputStream;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Frames the entities of one data model into a file. The model's own JSON for one entity is
 *  {@link TargetModel#serialize}; what goes around and between entities is the exporter's. */
public interface EntityExporter {
    String model();

    String mediaType();

    /** Writes every entity without closing {@code out}. A failed write throws
     *  {@link java.io.UncheckedIOException}; an entity the model cannot write throws
     *  {@link IllegalArgumentException}. Both can come after part of the output is written. */
    void export(Stream<ModelEntity> entities, OutputStream out);

    /** Writes every entity a project holds in this exporter's model, as {@link #export(Stream, OutputStream)}. */
    default void export(StatementRepository statements, String projectId, OutputStream out) {
        statements.entities(projectId, model(), entities -> {
            export(entities, out);
            return null;
        });
    }

    static EntityExporter forModel(String model) {
        Map<String, Supplier<EntityExporter>> exporters = Map.of("ftm", FtmJsonExporter::new);
        Supplier<EntityExporter> exporter = model == null ? null : exporters.get(model);
        if (exporter == null) {
            throw new UnknownTargetModel(model, exporters.keySet());
        }
        return exporter.get();
    }
}
