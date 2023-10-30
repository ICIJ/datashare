package org.icij.datashare.db;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.document.TikaDocument;
import org.icij.spewer.FieldNames;
import org.icij.spewer.Spewer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;

import static java.util.Optional.ofNullable;
import static org.apache.tika.metadata.HttpHeaders.*;

public class DatabaseSpewer extends Spewer {
    private final Project project;
    final Repository repository;
    private final LanguageGuesser languageGuesser;
    private static final String DEFAULT_VALUE_UNKNOWN = "unknown";

    DatabaseSpewer(Project project, Repository repository, LanguageGuesser languageGuesser) {
        super(new FieldNames());
        this.project = project;
        this.repository = repository;
        this.languageGuesser = languageGuesser;
    }

    @Override
    protected void writeDocument(TikaDocument tikaDocument, TikaDocument parent, TikaDocument root, int level) throws IOException {
        String content = toString(tikaDocument.getReader()).trim();
        Charset charset = Charset.forName(ofNullable(tikaDocument.getMetadata().get(CONTENT_ENCODING)).orElse("utf-8"));
        String contentType = ofNullable(tikaDocument.getMetadata().get(CONTENT_TYPE)).orElse(DEFAULT_VALUE_UNKNOWN).split(";")[0];
        long contentLength = Long.parseLong(ofNullable(tikaDocument.getMetadata().get(CONTENT_LENGTH)).orElse("-1"));
        String parentId = parent == null ? null: parent.getId();
        String rootId = root == null ? null: root.getId();

        Document document = DocumentBuilder.createDoc().
                with(project).
                withId(tikaDocument.getId()).
                with(tikaDocument.getPath()).
                with(Document.Status.PARSED).
                with(content).
                with(languageGuesser.guess(content)).
                with(charset).
                ofMimeType(contentType).
                with(getMetadata(tikaDocument)).
                with(new ArrayList<>()).
                extractedAt(new Date()).
                withParentId(parentId).
                withRootId(rootId).
                withExtractionLevel((short) level).
                withContentLength(contentLength).
                with(new Pipeline.Type[]{}).
                build();
        repository.create(document);
    }
}
