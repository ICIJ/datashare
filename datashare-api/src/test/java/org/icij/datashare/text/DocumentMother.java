package org.icij.datashare.text;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;

public class DocumentMother {
    public static Document getOneWithoutBlankValue() {
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
        ContentTypeCategory contentTypeCategory = ContentTypeCategory.AUDIO;
        return DocumentBuilder.createDoc()
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
                .with(contentTypeCategory)
                .withOcrParser(ocrParser).build();
    }
}
