package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.utils.CommonsDigester;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.extract.document.*;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.charset.Charset;

public class SourceExtractor {
    Logger LOGGER = LoggerFactory.getLogger(SourceExtractor.class);

    public InputStream getSource(final Document document) throws FileNotFoundException {
        return getSource(document.getProject(), document);
    }

    public InputStream getSource(final Project project, final Document document) throws FileNotFoundException {
        if (document.isRootDocument()) {
            return new FileInputStream(document.getPath().toFile());
        } else {
            try {
                LOGGER.info("extracting embedded document " + Identifier.shorten(document.getId(), 4) + " from root document " + document.getPath());
                CommonsDigester digester = new CommonsDigester(20 * 1024 * 1024, "SHA256");
                EmbeddedDocumentMemoryExtractor embeddedExtractor = new EmbeddedDocumentMemoryExtractor(digester, Document.HASHER.toString());
                TikaDocument rootDocument = new DocumentFactory().withIdentifier(
                        new DigestIdentifier(Document.HASHER.toString(), Charset.defaultCharset())).
                        create(document.getPath());
                TikaDocumentSource source = embeddedExtractor.extract(rootDocument, document.getId());
                return new ByteArrayInputStream(source.content);
            } catch (SAXException | TikaException | IOException e) {
                throw new ExtractException("extract error for embedded document " + document.getId(), e);
            }
        }
    }
}
