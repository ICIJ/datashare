package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Project.project;

public class SourceExtractorTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    @Test
    public void test_get_source_for_root_doc() throws IOException {
        Document document = DocumentBuilder.createDoc(project("project"),get(getClass().getResource("/docs/embedded_doc.eml").getPath()))
                .with("it has been parsed")
                .with(Language.FRENCH)
                .with(Charset.defaultCharset())
                .ofMimeType("message/rfc822")
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
                .ofMimeType("application/msword")
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
        DocumentFactory tikaFactory = new DocumentFactory().configure(Options.from(new HashMap<String, String>() {{
            put("idDigestMethod", Document.HASHER.toString());
        }}));
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Extractor extractor = new Extractor(tikaFactory);
        extractor.setDigester(new UpdatableDigester(TEST_INDEX, Document.HASHER.toString()));
        final TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
                l -> Language.ENGLISH, new FieldNames(), Mockito.mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex(TEST_INDEX);
        spewer.write(document);

        Document attachedPdf = new ElasticsearchIndexer(es.client, new PropertiesProvider()).
                get(TEST_INDEX, "1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca",
                        "f4078910c3e73a192e3a82d205f3c0bdb749c4e7b23c1d05a622db0f07d7f0ededb335abdb62aef41ace5d3cdb9298bc");

        assertThat(attachedPdf).isNotNull();
        assertThat(attachedPdf.getContentType()).isEqualTo("application/pdf");
        InputStream source = new SourceExtractor().getSource(project(TEST_INDEX), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(49779);
    }

    @Test
    public void test_get_source_for_embedded_doc_without_metadata() throws Exception {
        DocumentFactory tikaFactory = new DocumentFactory().configure(Options.from(new HashMap<String, String>() {{
            put("idDigestMethod", Document.HASHER.toString());
        }}));
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Extractor extractor = new Extractor(tikaFactory);
        extractor.setDigester(new UpdatableDigester(TEST_INDEX, Document.HASHER.toString()));
        final TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
                l -> Language.ENGLISH, new FieldNames(), Mockito.mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex(TEST_INDEX);
        spewer.write(document);

        Document attachedPdf = new ElasticsearchIndexer(es.client, new PropertiesProvider()).
                get(TEST_INDEX, "1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca",
                        "f4078910c3e73a192e3a82d205f3c0bdb749c4e7b23c1d05a622db0f07d7f0ededb335abdb62aef41ace5d3cdb9298bc");

        InputStream source = new SourceExtractor(true).getSource(project(TEST_INDEX), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source).length).isNotEqualTo(49779);
    }

    @Test
    public void test_get_source_for_embedded_doc_in_zip_archive() throws Exception {
        DocumentFactory tikaFactory = new DocumentFactory().configure(Options.from(new HashMap<String, String>() {{
            put("idDigestMethod", Document.HASHER.toString());
        }}));
        Path path = get(getClass().getResource("/docs/lorem-ipsum.zip").getPath());
        Extractor extractor = new Extractor(tikaFactory);
        extractor.setDigester(new UpdatableDigester(TEST_INDEX, Document.HASHER.toString()));
        final TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
                l -> Language.ENGLISH, new FieldNames(), Mockito.mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex(TEST_INDEX);
        spewer.write(document);

        Document attachedPdf = new ElasticsearchIndexer(es.client, new PropertiesProvider()).
                get(TEST_INDEX, "67c3f34a1d87aabe8d38330be25941cc6c7d2252587cfc6dbb1322feb6bf5bd934e0ecee0096a815dfcf4a6512232f88",
                        "978b9bb0a4fd5c76616e19f9fde017b82be0a30ae2fb5e2b990111dfa6d3fb72bbf5c9564a40d1717b0159896c07702d");

        assertThat(attachedPdf).isNotNull();
        assertThat(attachedPdf.getContentType()).isEqualTo("application/pdf");
        InputStream source = new SourceExtractor().getSource(project(TEST_INDEX), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(77123);
    }

    @Test
    public void test_get_source_for_embedded_doc_in_tar_gz_archive() throws Exception {
        DocumentFactory tikaFactory = new DocumentFactory().configure(Options.from(new HashMap<String, String>() {{
            put("idDigestMethod", Document.HASHER.toString());
        }}));
        Path path = get(getClass().getResource("/docs/lorem-ipsum.tar.gz").getPath());
        Extractor extractor = new Extractor(tikaFactory);
        extractor.setDigester(new UpdatableDigester(TEST_INDEX, Document.HASHER.toString()));
        final TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
                l -> Language.ENGLISH, new FieldNames(), Mockito.mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex(TEST_INDEX);
        spewer.write(document);

        Document attachedPdf = new ElasticsearchIndexer(es.client, new PropertiesProvider()).
                get(TEST_INDEX, "58a0f089a5d511b52838aa4f0737d6bf3a033e4d566f88fe01b8a37e1d69e3f66cb07365f4fa0afac4522e726b5ac361",
                        "63a43ea0728ce393e09e0c2afdceafd1db5e5d0c3451edca0cfba8e0a70b7e24c9a7e89ef7cd593a937a08cbeb7cad4a");

        assertThat(attachedPdf).isNotNull();
        assertThat(attachedPdf.getContentType()).isEqualTo("application/pdf");
        InputStream source = new SourceExtractor().getSource(project(TEST_INDEX), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(77123);
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
