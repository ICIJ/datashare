package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
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
            put("idDigestMethod", Hasher.SHA_256.toString());
        }}));
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        final TikaDocument document = tikaFactory.create(path);
        Extractor extractor = new Extractor();
        Reader reader = extractor.extract(document);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
                new OptimaizeLanguageGuesser(), new FieldNames(), Mockito.mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex(TEST_INDEX);
        spewer.write(document, reader);

        Document attachedPdf = new ElasticsearchIndexer(es.client, new PropertiesProvider()).
                get(TEST_INDEX, "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e", "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e");

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
