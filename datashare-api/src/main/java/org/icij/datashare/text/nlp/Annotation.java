package org.icij.datashare.text.nlp;

import java.util.*;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;


/**
 * Annotation on a {@link Document}
 * consists of {@link Tag}s indexed by {@link NlpStage}s
 * produced by an {@link Pipeline}
 *
 * Created by julien on 8/17/16.
 */
public class Annotation {

    // Annotated document (hash)
    private final String document;

    // Annotator
    private final Pipeline.Type pipeline;

    // Annotator language
    private final Language language;

    // Tags
    private final Map<NlpStage, List<Tag>> tags;


    public Annotation(String document, Pipeline.Type pipeline, Language language) {
        this.document = document;
        this.pipeline = pipeline;
        this.language = language;
        tags = new HashMap<NlpStage, List<Tag>>() {{
            Arrays.stream( NlpStage.values() )
                    .forEach( stage ->
                            put(stage, new ArrayList<>())
                    );
        }};
    }

    public String getDocument() {
        return document;
    }

    public Language getLanguage() { return language; }

    public Pipeline.Type getPipeline() {
        return pipeline;
    }

    public List<Tag> get(NlpStage stage) {
        return tags.get(stage);
    }

    public void add(NlpStage stage, int begin, int end) {
        tags.get(stage).add(new Tag(stage, begin, end));
    }

    public void add(NlpStage stage, int begin, int end, String value) {
        tags.get(stage).add(new Tag(stage, begin, end, value));
    }

}
