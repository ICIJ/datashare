String getTranslationContent(def _source, def targetLanguage) {
    boolean hasContent = _source.content_translated !== null && _source.content_translated.length > 0;
    if (hasContent) {
        def translations = _source.content_translated;
        translations.removeIf(s -> !s.target_language.equals(targetLanguage));
        if (translations.length == 0) {
            throw new IllegalArgumentException();
        }
        //select first translations of maybe many with same target (different interpreters)
        return translations[0].content;
    } else {
        throw new IllegalArgumentException();
    }
}

ArrayList getOffsets(String query,String content){
    def offsets = new ArrayList();
    int queryLength = query.length();
    int lastIndex = content.indexOf(query);
    while (lastIndex != -1) {
        offsets.add(lastIndex);
        lastIndex = content.indexOf(query, lastIndex + queryLength);
    }
    return offsets;
}

if(params.targetLanguage != null){
    String content = getTranslationContent(params._source,params.targetLanguage);
    def offsets = getOffsets(params.query,content);
    return [
            "query": params.query,
            "offsets":offsets,
            "count":offsets.length,
            "targetLanguage":params.targetLanguage
    ];
} else {
    String content = params._source.content;
    def offsets = getOffsets(params.query,content);
    return [
            "query": params.query,
            "offsets":offsets,
            "count":offsets.length
    ];
}
