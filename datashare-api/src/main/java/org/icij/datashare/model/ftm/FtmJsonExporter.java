package org.icij.datashare.model.ftm;

import org.icij.datashare.model.EntityExporter;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Iterator;
import java.util.stream.Stream;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Writes plain FtM entity JSON, one entity per line, so any FtM tool reads the file. The ontology
 *  version and the source documents of an entity stay out because FtM's entity JSON has no field for
 *  them: the statements remain where both are recorded. */
public class FtmJsonExporter implements EntityExporter {
    private static final String MODEL = "ftm";

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public String mediaType() {
        return "application/x-ndjson";
    }

    @Override
    public String extension() {
        return "ndjson";
    }

    @Override
    public void export(Stream<ModelEntity> entities, OutputStream out) {
        TargetModel ftm = TargetModelRegistry.get(MODEL);
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8));
        try {
            for (Iterator<ModelEntity> it = entities.iterator(); it.hasNext(); ) {
                ModelEntity entity = it.next();
                writer.write(ftm.serialize(entity));
                writer.write('\n');
            }
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
