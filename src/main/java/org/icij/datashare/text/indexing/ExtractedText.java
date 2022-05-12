package org.icij.datashare.text.indexing;

public class ExtractedText {
    public final String content;
    public final long maxOffset;
    public final long offset;
    public final long limit;


    public ExtractedText(String content, long offset, long limit, long maxOffset) {
        this.content = content;
        this.offset = offset;
        this.limit = limit;
        this.maxOffset = maxOffset;
    }
}
