package org.icij.datashare.text.nlp;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;

import java.util.*;


public class Annotations {
    private final String documentId;
    private final Pipeline.Type pipelineType;
    private final Language language;
    private final Map<NlpStage, List<NlpTag>> tags;

    public Annotations(String documentId, Pipeline.Type pipelineType, Language language) {
        this.documentId = documentId;
        this.pipelineType = pipelineType;
        this.language = language;
        tags = new HashMap<NlpStage, List<NlpTag>>() {{
            Arrays.stream( NlpStage.values() )
                    .forEach( stage ->
                            put(stage, new ArrayList<>())
                    );
        }};
    }

    public String getDocumentId() {
        return documentId;
    }
    public Language getLanguage() { return language; }
    public Pipeline.Type getPipelineType() {
        return pipelineType;
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
