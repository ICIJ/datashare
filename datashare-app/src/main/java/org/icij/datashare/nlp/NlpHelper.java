package org.icij.datashare.nlp;

import java.util.Map;
import org.icij.datashare.text.nlp.Pipeline;

public class NlpHelper {
    public static Map<String, Object> pipelineExtras(Pipeline.Type pipeline) {
        if (pipeline == Pipeline.Type.SPACY) {
            return Map.of("modelSize", "md");
        }
        return Map.of();
    }
}
