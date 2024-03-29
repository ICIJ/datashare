package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.DigestingParser;
import org.apache.tika.parser.digestutils.CommonsDigester;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.Mode;
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

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPTION;


public class SourceExtractor {
    Logger LOGGER = LoggerFactory.getLogger(SourceExtractor.class);
    private final PropertiesProvider propertiesProvider;
    private final boolean filterMetadata;
    private final MetadataCleaner metadataCleaner = new MetadataCleaner();

    public SourceExtractor() {
        this(new PropertiesProvider(), false);
    }

    public SourceExtractor(PropertiesProvider propertiesProvider) {
        this(propertiesProvider, false);
    }

    public SourceExtractor( boolean filterMetadata) {
        this.propertiesProvider = new PropertiesProvider();
        this.filterMetadata = filterMetadata;
    }

    public SourceExtractor(PropertiesProvider propertiesProvider, boolean filterMetadata) {
        this.propertiesProvider = propertiesProvider;
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
            LOGGER.info("Extracting embedded document " + Identifier.shorten(document.getId(), 4) + " from root document " + document.getPath());
            return getEmbeddedSource(project, document);
        }
    }

    public InputStream getEmbeddedSource(final Project project, final Document document) {
        Hasher hasher = Hasher.valueOf(document.getId().length());
        String algorithm = hasher.toString();
        int i = 0;
        List<DigestingParser.Digester> digesters = new ArrayList<>(List.of());
        // Digester with the project name
        digesters.add(new CommonsDigester(20 * 1024 * 1024,  algorithm.replace("-", "")));
        // Digester with the project name
        digesters.add(new UpdatableDigester(project.getId(), algorithm));
        // Digester with the project name set on "defaultProject" for retro-compatibility
        if (mightUseLegacyDigester(document)) {
            digesters.add(new UpdatableDigester(getDefaultProject(), algorithm));
        }

        // Try each digester to find embedded doc and ensure we 
        // used every available digesters to find it.
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
            } catch (ContentNotFoundException | SAXException | TikaException | IOException ex) {
                LOGGER.info(String.format("Extract attempt %s/%s for embedded document failed:", ++i, digesters.size()));
                LOGGER.info(String.format("\t├── exception: %s",  ex.getClass().getSimpleName()));
                LOGGER.info(String.format("\t├── algorithm: %s",  algorithm));
                LOGGER.info(String.format("\t├── digester: %s",  digester.getClass().getSimpleName()));
                LOGGER.info(String.format("\t├── id: %s", document.getId()));
                LOGGER.info(String.format("\t├── routing: %s",  document.getRootDocument()));
                LOGGER.info(String.format("\t└── project: %s",  document.getProject().getName()));
            }
        }

        throw new ContentNotFoundException(document.getRootDocument(), document.getId());
    }

    private boolean mightUseLegacyDigester(Document document) {
        return !isServerMode() && document.getExtractionLevel() > 0 && !document.getProject().name.equals(getDefaultProject());
    }

    private String getDefaultProject() {
        return this.propertiesProvider.get(DEFAULT_PROJECT_OPTION).orElse("local-datashare");
    }

    private boolean isServerMode() {
        return propertiesProvider.get("mode").orElse(Mode.SERVER.name()).equals((Mode.SERVER.name()));
    }


}