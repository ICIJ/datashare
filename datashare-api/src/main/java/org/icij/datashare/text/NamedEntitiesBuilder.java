package org.icij.datashare.text;

import org.icij.datashare.function.Pair;
import org.icij.datashare.text.nlp.Pipeline;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NamedEntitiesBuilder {
    private final Pipeline.Type type;
    private final String docId;
    private final Language language;
    private final Map<Pair<String, NamedEntity.Category>, List<Long>> mentionIndicesMap = new LinkedHashMap<>();
    private Map<String, Object> metadata;

    private String rootId;


    public NamedEntitiesBuilder(Pipeline.Type type, String docId, Language language, Map<String, Object> metadata) {
        this.type = type;
        this.docId = docId;
        this.language = language;
        this.rootId = docId;
        this.metadata = metadata;
    }

    public NamedEntitiesBuilder(Pipeline.Type type, String docId, Language language) {
        this(type, docId, language, null);
    }

    public List<NamedEntity> build() {
        return mentionIndicesMap.entrySet().stream().map(e ->
                NamedEntity.create(e.getKey()._2(), e.getKey()._1(), e.getValue(), docId, rootId, type, language, metadata)).
                collect(Collectors.toList());
    }

    public NamedEntitiesBuilder add(NamedEntity.Category category, String mention, long index) {
        mentionIndicesMap.putIfAbsent(new Pair<>(mention, category), new LinkedList<>());
        mentionIndicesMap.computeIfPresent(new Pair<>(mention, category), (k, v) -> { v.add(index); return v;});
        return this;
    }

    public NamedEntitiesBuilder withRoot(String rootId) {
        this.rootId = rootId;
        return this;
    }
    public NamedEntitiesBuilder withMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }
}
