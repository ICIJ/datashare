package org.icij.datashare.text.nlp;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;

import java.util.*;


public class Annotations {
    public final String documentId;
    public final String rootId;
    public final Pipeline.Type pipelineType;
    public final Language language;
    private final Map<NlpStage, List<NlpTag>> tags;

    public Annotations(String documentId, Pipeline.Type pipelineType, Language language) {
        this(documentId, documentId, pipelineType, language);
    }
    public Annotations(String documentId, String rootId, Pipeline.Type pipelineType, Language language) {
        this.documentId = documentId;
        this.rootId = rootId;
        this.pipelineType = pipelineType;
        this.language = language;
        tags = new HashMap<NlpStage, List<NlpTag>>() {{
            Arrays.stream( NlpStage.values() )
                    .forEach( stage ->
                            put(stage, new ArrayList<>())
                    );
        }};
    }

    public List<NlpTag> get(NlpStage stage) {
        return tags.get(stage);
    }
    public void add(NlpStage stage, int begin, int end) {
        tags.get(stage).add(new NlpTag(stage, begin, end));
    }
    public void add(NlpStage stage, int begin, int end, NamedEntity.Category value) {
        tags.get(stage).add(new NlpTag(stage, begin, end, value));
    }
}
