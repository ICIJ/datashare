package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
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
        Document document = new Document(project("project"), get(getClass().getResource("/docs/embedded_doc.eml").getPath()), "it has been parsed",
                    Language.FRENCH, Charset.defaultCharset(), "message/rfc822", new HashMap<>(),
                    Document.Status.INDEXED, 45L);

        InputStream source = new SourceExtractor().getSource(document);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(70574);
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
                new OptimaizeLanguageGuesser(), new FieldNames(), Mockito.mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex(TEST_INDEX);
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

    private byte[] getBytes(InputStream source) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nbTmpBytesRead;
        for(byte[] tmp = new byte[8192]; (nbTmpBytesRead = source.read(tmp)) > 0;) {
            buffer.write(tmp, 0, nbTmpBytesRead);
        }
        return buffer.toByteArray();
    }
}
