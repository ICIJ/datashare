package org.icij.datashare.model;

import org.icij.datashare.model.ftm.FtmJsonExporter;
import java.io.OutputStream;
import java.util.List;
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

    static EntityExporter forModel(String model) {
        return switch (model) {
            case "ftm" -> new FtmJsonExporter();
            default -> throw new UnknownTargetModel(model, List.of("ftm"));
        };
    }
}
