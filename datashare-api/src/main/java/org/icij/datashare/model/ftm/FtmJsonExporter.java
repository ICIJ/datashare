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
