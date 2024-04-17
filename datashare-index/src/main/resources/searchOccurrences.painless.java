String getTranslationContent(def _source, def targetLanguage) {
    if (targetLanguage == null || targetLanguage == "original") {
        return _source.content;
    }
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

String removeDiacritics(String input) {
    StringBuilder output = new StringBuilder();
    for (char c : input.toCharArray()) {
        if (Character.getType(c) != Character.NON_SPACING_MARK) {
            output.append(c);
        }
    }
    return output.toString();
}
String normalizeLetters(String input) {
    StringBuilder output = new StringBuilder();
    for (char c : input.toCharArray()) {
        if (Character.getType(c) == Character.LOWERCASE_LETTER) {
            String n = Normalizer.normalize(Character.toString(c) , Normalizer.Form.NFKD);
            output.append(removeDiacritics(n));
        }else{
            output.append(c);
        }
    }
    return output.toString();
}

ArrayList getOffsets(String query, String content) {
    def offsets = new ArrayList();
    String contentInLower = normalizeLetters(content.toLowerCase());
    String queryInLower = normalizeLetters(query.toLowerCase());
    int queryLength = query.length();
    int lastIndex = contentInLower.indexOf(queryInLower);
    while (lastIndex != -1) {
        offsets.add(lastIndex);
        lastIndex = contentInLower.indexOf(queryInLower, lastIndex + queryLength);
    }
    return offsets;
}

String content = getTranslationContent(params._source, params.targetLanguage);
def offsets = getOffsets(params.query, content);

return [
    "query": params.query,
    "offsets": offsets,
    "count": offsets.length,
    "targetLanguage":params.targetLanguage
];