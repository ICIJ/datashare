package org.icij.datashare.text;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import org.junit.Test;

public class DocumentBuilderTest {
    @Test
    public void test_build() {
        // Given
        String docId = "someId";
        String rootId = "rootId";
        String parentId = "parentId";
        short extractionLevel = (short) 1;
        long contentLength = 11L;
        Project project = Project.project("someProject");
        Tag sometag = Tag.tag("sometag");
        Document.Status status = Document.Status.INDEXED;
        Date extractionDate = Date.from(Instant.now());
        String mimeType = "SOME/MIME";
        List<Map<String, String>> contentTranslated = List.of(Map.of("french", "ducontenu"));
        Map<String, Object> metadata = Map.of("some", "metadata");
        String ocrParser = "org.apache.tika.parser.ocr.TesseractOCRParser";
        DocumentBuilder builder = DocumentBuilder.createDoc()
            .withDefaultValues(docId)
            .withRootId(rootId)
            .withParentId(parentId)
            .withExtractionLevel(extractionLevel)
            .withContentLength(contentLength)
            .with(project)
            .with(sometag)
            .with(status)
            .with(CORENLP)
            .extractedAt(extractionDate)
            .with(Charset.defaultCharset())
            .ofContentType(mimeType)
            .with(contentTranslated)
            .with(metadata)
            .with(Language.ENGLISH)
            .with(Path.of("some/path"))
            .with("somecontent")
            .withOcrParser(ocrParser);
        // When
        Document doc = builder.build();
        // Then
        assertThat(doc.getId()).isEqualTo(docId);
        assertThat(doc.getRootDocument()).isEqualTo(rootId);
        assertThat(doc.getParentDocument()).isEqualTo(parentId);
        assertThat(doc.getExtractionLevel()).isEqualTo(extractionLevel);
        assertThat(doc.getContentLength()).isEqualTo(contentLength);
        assertThat(doc.getProject()).isEqualTo(project);
        assertThat(doc.getTags()).isEqualTo(Set.of(sometag));
        assertThat(doc.getStatus()).isEqualTo(status);
        assertThat(doc.getExtractionDate()).isEqualTo(extractionDate);
        assertThat(doc.getContentTranslated()).isEqualTo(contentTranslated);
        assertThat(doc.getMetadata()).includes(entry("some", "metadata"));
        assertThat(doc.getOcrParser()).isEqualTo(ocrParser);
    }
}
