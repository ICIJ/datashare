package org.icij.datashare.text.indexing;

import org.icij.datashare.text.Document;
import org.icij.extract.document.TikaDocument;

import java.io.IOException;
import java.io.Reader;

public interface Indexer2 {
    void index(TikaDocument document, Reader contentReader) throws IOException;
    Document get(String docId);
    void close();
}
