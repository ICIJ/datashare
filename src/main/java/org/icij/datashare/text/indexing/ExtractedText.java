package org.icij.datashare.text.indexing;

public class ExtractedText {
    public final String content;
    public final int maxOffset;
    public final int offset;
    public final int limit;


    public ExtractedText(String content, int offset, int limit, int maxOffset) {
        this.content = content;
        this.offset = offset;
        this.limit = limit;
        this.maxOffset = maxOffset;
    }
}
