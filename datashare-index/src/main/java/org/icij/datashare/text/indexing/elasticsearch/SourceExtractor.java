package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.DigestingParser;
import org.apache.tika.parser.digestutils.CommonsDigester;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Project;
import org.icij.extract.cleaner.MetadataCleaner;
import org.icij.extract.document.*;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor.ContentNotFoundException;
import org.icij.extract.extractor.UpdatableDigester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

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
            return getEmbeddedSource(project, document);
        }
    }

    public InputStream getEmbeddedSource(final Project project, final Document document) throws FileNotFoundException {
        Hasher hasher = Hasher.valueOf(document.getId().length());
        String algorithm = hasher.toString();
        List<DigestingParser.Digester> digesters = new ArrayList<>(List.of());
        digesters.add(new CommonsDigester(20 * 1024 * 1024, algorithm.replace("-", "")));
        digesters.add(new UpdatableDigester(project.getId(), algorithm));

        for (DigestingParser.Digester digester : digesters) {
            Identifier identifier = new DigestIdentifier(hasher.toString(), Charset.defaultCharset());
            TikaDocument rootDocument = new DocumentFactory().withIdentifier(identifier).create(document.getPath());
            EmbeddedDocumentMemoryExtractor embeddedExtractor = new EmbeddedDocumentMemoryExtractor(digester, algorithm, false);

            try {
                TikaDocumentSource source = embeddedExtractor.extract(rootDocument, document.getId());
                InputStream inputStream = new ByteArrayInputStream(source.content);
                if (filterMetadata) {
                    return new ByteArrayInputStream(metadataCleaner.clean(inputStream).getContent());
                }
                return inputStream;
            } catch (FileNotFoundException | ContentNotFoundException _e) {
                continue;
            } catch (SAXException | TikaException | IOException exception) {
                String message = String.format("extract error for embedded document in project %s / id : %s / routing_id : %s", document.getProject().getName(), document.getId(), document.getRootDocument());
                throw new ExtractException(message, exception);
            }
        }

        throw new FileNotFoundException();
    }
}
