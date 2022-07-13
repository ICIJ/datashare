package org.icij.datashare.text.indexing;

public class SearchedText {
    public final int[] offsets;
    public final int count;
    public final String query;
    public String targetLanguage;

    public SearchedText(int[] offsets, int count, String query) {
        this.offsets = offsets;
        this.count = count;
        this.query = query;
        this.targetLanguage = null;
    }

    public SearchedText(int[] offsets, int count, String query, String targetLanguage) {
        this.offsets = offsets;
        this.count = count;
        this.query = query;
        this.targetLanguage = targetLanguage;
    }

}
