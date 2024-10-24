package org.icij.datashare.tasks;

import java.util.Objects;
import org.icij.datashare.text.nlp.Pipeline;

public class GroupHelper {
    // Later we could use enums if we have more groups
    public static final String PYTHON_GROUP = "Python";
    public static final String JAVA_GROUP = "Java";

    public static String nlpGroup(Pipeline.Type pipeline) {
        if (Objects.requireNonNull(pipeline) == Pipeline.Type.SPACY) {
            return PYTHON_GROUP;
        }
        return JAVA_GROUP;
    }
}
