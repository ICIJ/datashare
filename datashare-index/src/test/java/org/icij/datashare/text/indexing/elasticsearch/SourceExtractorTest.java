package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Project.project;

public class SourceExtractorTest {
    @ClassRule static public TemporaryFolder tmpDir = new TemporaryFolder();
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    @Test(expected = FileNotFoundException.class)
    public void test_file_not_found() throws IOException {
        File file = tmpDir.newFile("foo.bar");
        Document document = DocumentBuilder.createDoc(project("project"), file.toPath()).build();
        assertThat(file.delete()).isTrue();
        new SourceExtractor().getSource(document);
    }

    @Test(expected = EmbeddedDocumentMemoryExtractor.ContentNotFoundException.class)
    public void test_content_not_found() {
        Document document = DocumentBuilder.createDoc(project("project"), get(getClass().getResource("/docs/embedded_doc.eml").getPath()))
                .with("it has been parsed")
                .with(Language.FRENCH)
                .with(Charset.defaultCharset())
                .ofContentType("message/rfc822")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .withContentLength(45L).build();
        new SourceExtractor().getEmbeddedSource(project("project"), document);
    }

    @Test
    public void test_get_source_for_root_doc() throws IOException {
        Document document = DocumentBuilder.createDoc(project("project"),get(getClass().getResource("/docs/embedded_doc.eml").getPath()))
                .with("it has been parsed")
                .with(Language.FRENCH)
                .with(Charset.defaultCharset())
                .ofContentType("message/rfc822")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .withContentLength(45L).build();

        InputStream source = new SourceExtractor().getSource(document);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(70574);
    }

    @Test
    public void test_get_source_for_doc_and_pdf_with_without_metadata() throws IOException {
        Document document = DocumentBuilder.createDoc(project("project"),get(getClass().getResource("/docs/office_document.doc").getPath()))
                .with((String) null)
                .with(Language.ENGLISH)
                .with(Charset.defaultCharset())
                .ofContentType("application/msword")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .withContentLength(0L).build();

        InputStream inputStreamWithMetadata = new SourceExtractor(false).getSource(document);
        InputStream inputStreamWithoutMetadata = new SourceExtractor(true).getSource(document);
        assertThat(inputStreamWithMetadata).isNotNull();
        assertThat(inputStreamWithoutMetadata).isNotNull();
        assertThat(getBytes(inputStreamWithMetadata).length).isEqualTo(9216);
        assertThat(getBytes(inputStreamWithoutMetadata).length).isNotEqualTo(9216);
    }

    @Test
    public void test_get_source_for_embedded_doc() throws Exception {
        Options<String> options = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
            put("digestProjectName", TEST_INDEX);
        }});
        DocumentFactory tikaFactory = new DocumentFactory().configure(options);
        Extractor extractor = new Extractor(tikaFactory).configure(options);

        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        final TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(createIndexer(),
                l -> Language.ENGLISH, new FieldNames(), new PropertiesProvider()).withIndex(TEST_INDEX);
        spewer.write(document);

        Document attachedPdf = createIndexer().
                get(TEST_INDEX, "1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca",
                        "f4078910c3e73a192e3a82d205f3c0bdb749c4e7b23c1d05a622db0f07d7f0ededb335abdb62aef41ace5d3cdb9298bc");

        assertThat(attachedPdf).isNotNull();
        assertThat(attachedPdf.getContentType()).isEqualTo("application/pdf");
        InputStream source = new SourceExtractor().getSource(project(TEST_INDEX), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(49779);
    }

    private static ElasticsearchIndexer createIndexer() {
        return new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    }

    @Test
    public void test_get_source_for_embedded_doc_without_metadata() throws Exception {
        Options<String> options = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
            put("digestProjectName", TEST_INDEX);
        }});
        DocumentFactory tikaFactory = new DocumentFactory().configure(options);
        Extractor extractor = new Extractor(tikaFactory).configure(options);

        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        final TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(createIndexer(),
                l -> Language.ENGLISH, new FieldNames(), new PropertiesProvider()).withIndex(TEST_INDEX);
        spewer.write(document);

        Document attachedPdf = createIndexer().
                get(TEST_INDEX, "1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca",
                        "f4078910c3e73a192e3a82d205f3c0bdb749c4e7b23c1d05a622db0f07d7f0ededb335abdb62aef41ace5d3cdb9298bc");

        InputStream source = new SourceExtractor(true).getSource(project(TEST_INDEX), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source).length).isNotEqualTo(49779);
    }

    private byte[] getBytes(InputStream source) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nbTmpBytesRead;
        for(byte[] tmp = new byte[8192]; (nbTmpBytesRead = source.read(tmp)) > 0;) {
            buffer.write(tmp, 0, nbTmpBytesRead);
        }
        return buffer.toByteArray();
    }
}
