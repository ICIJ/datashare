package org.icij.datashare.text.nlp;

import org.icij.datashare.text.Language;

import java.util.*;


public class Annotations {
    private final String documentId;
    private final Pipeline.Type pipelineType;
    private final Language language;
    private final Map<NlpStage, List<Tag>> tags;

    public Annotations(String documentId, Pipeline.Type pipelineType, Language language) {
        this.documentId = documentId;
        this.pipelineType = pipelineType;
        this.language = language;
        tags = new HashMap<NlpStage, List<Tag>>() {{
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
