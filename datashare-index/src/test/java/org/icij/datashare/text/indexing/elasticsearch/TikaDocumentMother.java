package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParsingReader;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static java.nio.file.Paths.get;

public class TikaDocumentMother {

    public static TikaDocument oneWithContentType(String contentType) {
        Metadata metadata = new Metadata();
        metadata.add(TikaDocument.CONTENT_TYPE, contentType);
        final ParsingReader reader;
        try {
            reader = new ParsingReader(new ByteArrayInputStream("this content should be truncated".getBytes()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        TikaDocument res = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("fake-file.txt"), metadata);
        res.setReader(reader);
        return res;
    }
}
