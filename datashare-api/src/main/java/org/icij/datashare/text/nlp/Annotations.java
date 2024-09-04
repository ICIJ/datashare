package org.icij.datashare.text.nlp;

import java.util.ArrayList;
import java.util.List;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;

public class Annotations {
    public final String documentId;
    public final String rootId;
    public final Pipeline.Type pipelineType;
    public final Language language;
    private final List<NlpTag> tags;

    public Annotations(String documentId, Pipeline.Type pipelineType, Language language) {
        this(documentId, documentId, pipelineType, language);
    }

    public Annotations(String documentId, String rootId, Pipeline.Type pipelineType, Language language) {
        this.documentId = documentId;
        this.rootId = rootId;
        this.pipelineType = pipelineType;
        this.language = language;
        tags = new ArrayList<>();
    }

    public List<NlpTag> getTags() {
        return tags;
    }

    public void add(int begin, int end, NamedEntity.Category value) {
        tags.add(new NlpTag(begin, end, value));
    }
}
