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
int maxOffset = 0;
int end = params.offset+params.limit;
try{
    if(params.targetLanguage != null) {
        String content = getTranslationContent(params._source,params.targetLanguage);
        maxOffset = content.length();
        String contentResized = content.substring(params.offset, end);
        return [
            "content": contentResized,
            "maxOffset":maxOffset,
            "offset":params.offset,
            "limit":params.limit,
            "targetLanguage":params.targetLanguage
        ];
    } else {
        String content = params._source.content;
        String contentResized = content.substring(params.offset, end);
        maxOffset = content.length();
        return [
            "content": contentResized,
            "maxOffset":maxOffset,
            "offset":params.offset,
            "limit":params.limit
        ];
    }
} catch (IllegalArgumentException e) {
    return ["error":'Translated content in '+params.targetLanguage+' not found',"code":404];
} catch (StringIndexOutOfBoundsException e) {
    return ["error":"Range ["+params.offset+"-"+end+"] is out of document range ([0-"+maxOffset+"])","code":400];
}
