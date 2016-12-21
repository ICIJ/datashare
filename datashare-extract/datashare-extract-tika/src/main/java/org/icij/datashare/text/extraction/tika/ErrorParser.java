package org.icij.datashare.text.extraction.tika;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.Set;

public class ErrorParser implements Parser {

    private final Parser parser;

    private final Set<MediaType> excludedTypes;


    public ErrorParser(Parser parser, Set<MediaType> excludedTypes) {
        this.parser = parser;
        this.excludedTypes = excludedTypes;
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return parser.getSupportedTypes(context);
    }

    @Override
    public void parse(InputStream inputStream, ContentHandler contentHandler, Metadata metadata, ParseContext context) throws TikaException {
        final MediaType unsupportedType = MediaType.parse(metadata.get(Metadata.CONTENT_TYPE));

        if (null == unsupportedType) {
            throw new TikaException("Unable to parse unsupported media type: " + metadata.get(Metadata.CONTENT_TYPE) + ".");
        }

        // If the MIME type is supported by any of the excluded parsers, send a special exception to signal the reason.
        for (MediaType excludedType : excludedTypes) {
            if (unsupportedType.equals(excludedType)) {
                throw new ExcludedMediaTypeException("Excluded media type: " + unsupportedType);
            }
        }

        throw new TikaException("Unsupported media type: " + unsupportedType + ".");
    }
}

