package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.DigestingParser;
import org.apache.tika.parser.digestutils.CommonsDigester;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Project;
import org.icij.extract.cleaner.MetadataCleaner;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.Identifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.document.TikaDocumentSource;
import org.icij.extract.extractor.EmbeddedDocumentExtractor;
import org.icij.extract.extractor.EmbeddedDocumentExtractor.ContentNotFoundException;
import org.icij.extract.extractor.UpdatableDigester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;


public class SourceExtractor {
    Logger LOGGER = LoggerFactory.getLogger(SourceExtractor.class);
    private final PropertiesProvider propertiesProvider;
    private final boolean filterMetadata;
    private final MetadataCleaner metadataCleaner = new MetadataCleaner();

    public SourceExtractor(PropertiesProvider propertiesProvider) {
        this(propertiesProvider, false);
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
        int i = 0;
        List<DigestingParser.Digester> digesters = new ArrayList<>(List.of());
        // Digester without the project name
        digesters.add(new CommonsDigester(20 * 1024 * 1024,  hasher.toStringWithoutDash()));
        // Digester with the project name
        digesters.add(new UpdatableDigester(project.getId(), hasher.toString()));
        // Digester with the project name set on "defaultProject" for retro-compatibility
        if (mightUseLegacyDigester(document)) {
            digesters.add(new UpdatableDigester(getDefaultProject(), hasher.toString()));
        }

        // Try each digester to find embedded doc and ensure we 
        // used every available digesters to find it.
        for (DigestingParser.Digester digester : digesters) {
            Identifier identifier = new DigestIdentifier(hasher.toString(), Charset.defaultCharset());
            TikaDocument rootDocument = new DocumentFactory().withIdentifier(identifier).create(document.getPath());

            try {
                EmbeddedDocumentExtractor embeddedExtractor = new EmbeddedDocumentExtractor(
                        digester, hasher.toString(),
                        getArtifactPath(project),false);
                TikaDocumentSource source = embeddedExtractor.extract(rootDocument, document.getId());
                InputStream inputStream = source.get();
                if (filterMetadata) {
                    return new ByteArrayInputStream(metadataCleaner.clean(inputStream).getContent());
                }
                return inputStream;
            } catch (ContentNotFoundException | SAXException | TikaException | IOException ex) {
                LOGGER.debug("Extract attempt {}/{} for embedded document {}/{} failed (algorithm={}, digester={}, project={})",
                        ++i, digesters.size(),
                        document.getId(), document.getRootDocument(),
                        hasher, digester.getClass().getSimpleName(),
                        document.getProject(), ex);
            }
        }

        throw new ContentNotFoundException(document.getRootDocument(), document.getId());
    }

    public void extractEmbeddedSources(final Project project, Document document) throws TikaException, IOException, SAXException {
        Hasher hasher = Hasher.valueOf(document.getId().length());
        DigestingParser.Digester digester = noDigestProject() ?
                new CommonsDigester(20 * 1024 * 1024,  hasher.toStringWithoutDash()):
                new UpdatableDigester(project.getId(), hasher.toString());

        Identifier identifier = new DigestIdentifier(hasher.toString(), Charset.defaultCharset());
        TikaDocument tikaDocument = new DocumentFactory().withIdentifier(identifier).create(document.getPath());
        EmbeddedDocumentExtractor embeddedExtractor = new EmbeddedDocumentExtractor(digester, hasher.toString(), getArtifactPath(project),false);
        embeddedExtractor.extractAll(tikaDocument);
    }

    private Path getArtifactPath(Project project) {
        return propertiesProvider.get(DatashareCliOptions.ARTIFACT_DIR_OPT).map(dir -> Path.of(dir).resolve(project.name)).orElse(null);
    }

    private boolean noDigestProject() {
        return Boolean.parseBoolean(propertiesProvider.get(DatashareCliOptions.NO_DIGEST_PROJECT_OPT).orElse("true"));
    }

    private boolean mightUseLegacyDigester(Document document) {
        return !isServerMode() && document.getExtractionLevel() > 0 && !document.getProject().name.equals(getDefaultProject());
    }

    private String getDefaultProject() {
        return this.propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse("local-datashare");
    }

    private boolean isServerMode() {
        return propertiesProvider.get("mode").orElse(Mode.SERVER.name()).equals((Mode.SERVER.name()));
    }
}