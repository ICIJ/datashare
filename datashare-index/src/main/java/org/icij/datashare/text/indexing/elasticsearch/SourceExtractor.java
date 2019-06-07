package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.exception.TikaException;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.document.TikaDocumentSource;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.charset.Charset;

public class SourceExtractor {
    public InputStream getSource(final Document document) throws FileNotFoundException {
        return getSource(document.getProject(), document);
    }

    public InputStream getSource(final Project project, final Document document) throws FileNotFoundException {
        if (document.isRootDocument()) {
            return new FileInputStream(document.getPath().toFile());
        } else {
            try {
                UpdatableDigester digester = new UpdatableDigester(project.getId(), document.HASHER.toString());
                EmbeddedDocumentMemoryExtractor embeddedExtractor = new EmbeddedDocumentMemoryExtractor(digester);
                TikaDocument rootDocument = new DocumentFactory().withIdentifier(
                        new DigestIdentifier(document.HASHER.toString(), Charset.defaultCharset())).
                        create(document.getPath());
                TikaDocumentSource source = embeddedExtractor.extract(rootDocument, document.getId());
                return new ByteArrayInputStream(source.content);
            } catch (SAXException | TikaException | IOException e) {
                throw new ExtractException("extract error for embedded document " + document.getId(), e);
            }
        }
    }
}
