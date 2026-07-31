package org.icij.datashare.text.artifact;

import org.apache.tika.Tika;
import org.icij.datashare.PropertiesProvider;

import java.util.Map;

import static org.icij.datashare.cli.DatashareCliOptions.OCR_OPT;

/** The page artifact: a document's paginated PLAIN extracted text, written as a single
 *  pages/content.txt whose per-page half-open byte ranges live in the manifest entry. */
public class PageArtifact implements Artifact {
    private static final ArtifactType TYPE = ArtifactType.PAGE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";

    private final PropertiesProvider propertiesProvider;

    public PageArtifact(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public ArtifactType type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        // The run's OCR setting, not the per-document one: taskInput() gets no document, and its
        // contract keeps per-document state out so the same doc compares equal across batches.
        return Map.of("pipeline", "tika", "version", tikaVersion(), "ocr", ocrEnabled());
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    private boolean ocrEnabled() {
        return propertiesProvider.get(OCR_OPT).map(Boolean::parseBoolean).orElse(true);
    }

    private static String tikaVersion() {
        return Tika.getString().replace(TIKA_PREFIX, "").strip();
    }
}
