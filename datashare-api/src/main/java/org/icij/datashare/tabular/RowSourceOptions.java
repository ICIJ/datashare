package org.icij.datashare.tabular;

import java.nio.charset.Charset;

/**
 * Per-read knobs, every one nullable, a null meaning the reader's own default. A reader ignores the
 * fields it has no use for: {@code delimiter} and {@code quote} are delimited-text only, {@code
 * sheet} is Excel only, {@code table} is the Tika fallback only, and {@code charset} does not reach
 * the fallback, which lets Tika resolve encoding itself.
 */
public record RowSourceOptions(String contentType, Charset charset, Character delimiter,
                               Character quote, String sheet, Integer table) {

    public static RowSourceOptions defaults() {
        return new RowSourceOptions(null, null, null, null, null, null);
    }

    public RowSourceOptions withContentType(String newContentType) {
        return new RowSourceOptions(newContentType, charset, delimiter, quote, sheet, table);
    }

    public RowSourceOptions withCharset(Charset newCharset) {
        return new RowSourceOptions(contentType, newCharset, delimiter, quote, sheet, table);
    }

    public RowSourceOptions withDelimiter(Character newDelimiter) {
        return new RowSourceOptions(contentType, charset, newDelimiter, quote, sheet, table);
    }
}
