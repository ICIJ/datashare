int maxOffset = 0;
int end = params.offset+params.limit;

try {
    boolean hasContent= params._source.content_translated !== null && params._source.content_translated.length>0;
    if(hasContent) {
        def translations = params._source.content_translated;
        translations.removeIf(s -> !s.target_language.equals(params.targetLanguage));
        if (translations.length==0){
            throw new IllegalArgumentException();
        }
        //select first translations of maybe many with same target (different interpreters)
        String selectedTranslation = translations[0].content;

        maxOffset = selectedTranslation.length();
        String contentResized = selectedTranslation.substring(params.offset, end);
        return [
            "content": contentResized,
            "maxOffset":maxOffset,
            "offset":params.offset,
            "limit":params.limit,
            "targetLanguage": params.targetLanguage
        ];
    }
    else {
        throw new IllegalArgumentException();
    }
} catch (IllegalArgumentException e) {
    return ["error":'Translated content in '+params.targetLanguage+' not found',"code":404];
} catch (StringIndexOutOfBoundsException e) {
    return ["error":'Range ['+params.offset+'-'+end+'] is out of document range ([0-'+maxOffset+'])',"code":400];
}
