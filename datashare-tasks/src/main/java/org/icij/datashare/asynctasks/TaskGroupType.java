package org.icij.datashare.asynctasks;

import java.util.Objects;
import org.icij.datashare.text.nlp.Pipeline;

public enum TaskGroupType {
    Python, Java, Test;

    public static TaskGroupType nlpGroup(Pipeline.Type pipeline) {
        if (Objects.requireNonNull(pipeline) == Pipeline.Type.SPACY) {
            return Python;
        }
        return Java;
    }
}
