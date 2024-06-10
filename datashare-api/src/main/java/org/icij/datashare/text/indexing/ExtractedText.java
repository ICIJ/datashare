package org.icij.datashare.text.indexing;

public class ExtractedText {
    public final String content;
    public final int maxOffset;
    public final int offset;
    public final int limit;
    public String targetLanguage="";

    public ExtractedText(String content, int offset, int limit, int maxOffset) {
        this.content = content;
        this.offset = offset;
        this.limit = limit;
        this.maxOffset = maxOffset;
    }
    public ExtractedText(String content, int offset, int limit, int maxOffset, String targetLanguage) {
        this.content = content;
        this.offset = offset;
        this.limit = limit;
        this.maxOffset = maxOffset;
        this.targetLanguage = targetLanguage;
    }

}
