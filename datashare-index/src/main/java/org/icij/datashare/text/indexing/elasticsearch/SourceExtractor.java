package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.digestutils.CommonsDigester;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Project;
import org.icij.extract.cleaner.MetadataCleaner;
import org.icij.extract.document.*;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.charset.Charset;

import static org.icij.datashare.text.Hasher.SHA_384;

public class SourceExtractor {
    Logger LOGGER = LoggerFactory.getLogger(SourceExtractor.class);
    private final boolean filterMetadata;
    private final MetadataCleaner metadataCleaner = new MetadataCleaner();

    public SourceExtractor() {
        this(false);
    }

    public SourceExtractor(boolean filterMetadata) {
        this.filterMetadata = filterMetadata;
    }

    public InputStream getSource(final Document document) throws FileNotFoundException {
        return getSource(document.getProject(), document);
    }

    public InputStream getSource(final Project project, final Document document) throws FileNotFoundException {
        if (document.isRootDocument()) {
            if (filterMetadata) {
                try {
                    return new ByteArrayInputStream(metadataCleaner.clean(new FileInputStream(document.getPath().toFile())).getContent());
                } catch (IOException e) {
                    throw new ExtractException("content cleaner error ", e);
                }
            } else {
                return new FileInputStream(document.getPath().toFile());
            }
        } else {
            LOGGER.info("extracting embedded document " + Identifier.shorten(document.getId(), 4) + " from root document " + document.getPath());
            TikaDocumentSource source;
            EmbeddedDocumentMemoryExtractor embeddedExtractor;
            DigestIdentifier identifier;
            if (document.getId().length() == SHA_384.digestLength) {
                embeddedExtractor = new EmbeddedDocumentMemoryExtractor(new UpdatableDigester(project.getId(), SHA_384.toString()));
                identifier = new DigestIdentifier(SHA_384.toString(), Charset.defaultCharset());
            } else {
                // backward compatibility
                Hasher hasher = Hasher.valueOf(document.getId().length());
                embeddedExtractor = new EmbeddedDocumentMemoryExtractor(
                        new CommonsDigester(20 * 1024 * 1024, hasher.toString().replace("-", "")), hasher.toString(), false);
                identifier = new DigestIdentifier(hasher.toString(), Charset.defaultCharset());
            }
            TikaDocument rootDocument = new DocumentFactory().withIdentifier(identifier).create(document.getPath());
            try {
                source = embeddedExtractor.extract(rootDocument, document.getId());
                return filterMetadata ? new ByteArrayInputStream(metadataCleaner.clean(new ByteArrayInputStream(source.content)).getContent())
                        : new ByteArrayInputStream(source.content);
            } catch (SAXException | TikaException | IOException e) {
                throw new ExtractException("extract error for embedded document " + document.getId(), e);
            }
        }
    }
}
