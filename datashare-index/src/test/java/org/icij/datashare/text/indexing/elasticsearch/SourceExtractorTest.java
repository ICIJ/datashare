package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.EmbeddedDocumentExtractor;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NO_DIGEST_PROJECT_OPT;
import static org.icij.datashare.text.Project.project;

public class SourceExtractorTest {
    @Rule public TemporaryFolder tmpDir = new TemporaryFolder();
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    @Test(expected = FileNotFoundException.class)
    public void test_file_not_found() throws IOException {
        File file = tmpDir.newFile("foo.bar");
        Document document = DocumentBuilder.createDoc(project("project"), file.toPath()).build();
        assertThat(file.delete()).isTrue();
        new SourceExtractor(getPropertiesProvider()).getSource(document);
    }

    @Test(expected = EmbeddedDocumentExtractor.ContentNotFoundException.class)
    public void test_content_not_found() {
        Document document = DocumentBuilder.createDoc(project("project"), get(getClass().getResource("/docs/embedded_doc.eml").getPath()))
                .with("it has been parsed")
                .with(Language.FRENCH)
                .with(Charset.defaultCharset())
                .ofContentType("message/rfc822")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .withContentLength(45L).build();
        new SourceExtractor(getPropertiesProvider()).getEmbeddedSource(project("project"), document);
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

        InputStream source = new SourceExtractor(getPropertiesProvider()).getSource(document);
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

        InputStream inputStreamWithMetadata = new SourceExtractor(getPropertiesProvider(), false).getSource(document);
        InputStream inputStreamWithoutMetadata = new SourceExtractor(getPropertiesProvider(), true).getSource(document);
        assertThat(inputStreamWithMetadata).isNotNull();
        assertThat(inputStreamWithoutMetadata).isNotNull();
        assertThat(getBytes(inputStreamWithMetadata).length).isEqualTo(9216);
        assertThat(getBytes(inputStreamWithoutMetadata).length).isNotEqualTo(9216);
    }

    @Test
    public void test_get_source_for_embedded_doc() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Map<String, Object> stringProperties = Map.of(
            "digestAlgorithm", Document.DEFAULT_DIGESTER.toString(),
            "digestProjectName", es.getIndexName(),
            "defaultProject", es.getIndexName());
        ElasticsearchIndexer elasticsearchIndexer = indexDocument(stringProperties, path, stringProperties);

        Document attachedPdf = elasticsearchIndexer.
                get(es.getIndexName(), "1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca",
                        "f4078910c3e73a192e3a82d205f3c0bdb749c4e7b23c1d05a622db0f07d7f0ededb335abdb62aef41ace5d3cdb9298bc");

        assertThat(attachedPdf).isNotNull();
        assertThat(attachedPdf.getContentType()).isEqualTo("application/pdf");
        InputStream source = new SourceExtractor(new PropertiesProvider(stringProperties)).getSource(project(es.getIndexName()), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(49779);
    }

   @Test
    public void test_get_source_for_embedded_doc_with_artifact_dir() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Map<String, Object> stringProperties = Map.of(
            "digestAlgorithm", Document.DEFAULT_DIGESTER.toString(),
            "digestProjectName", es.getIndexName(),
            ARTIFACT_DIR_OPT, tmpDir.getRoot().toString(),
            "defaultProject", es.getIndexName());
        ElasticsearchIndexer elasticsearchIndexer = indexDocument(stringProperties, path, stringProperties);
        Document attachedPdf = elasticsearchIndexer.
                get(es.getIndexName(), "1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca",
                        "f4078910c3e73a192e3a82d205f3c0bdb749c4e7b23c1d05a622db0f07d7f0ededb335abdb62aef41ace5d3cdb9298bc");

        InputStream source = new SourceExtractor(new PropertiesProvider(stringProperties)).getSource(project(es.getIndexName()), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(tmpDir.getRoot().toPath().resolve(es.getIndexName()).toFile()).isDirectory();
        Path cachedArtifact = tmpDir.getRoot().toPath()
            .resolve(es.getIndexName())
               .resolve("1b")
               .resolve("f2")
               .resolve("1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca")
               .resolve("raw");
        assertThat(cachedArtifact.toFile()).isFile();
        assertThat(cachedArtifact.toFile()).hasSize(49779);
    }

    @Test
    public void test_get_source_for_embedded_doc_without_metadata() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Map<String, Object> stringProperties = Map.of(
            "digestAlgorithm", Document.DEFAULT_DIGESTER.toString(),
            "digestProjectName", es.getIndexName(),
            "defaultProject", es.getIndexName());
        ElasticsearchIndexer elasticsearchIndexer = indexDocument(stringProperties, path, stringProperties);

        Document attachedPdf = elasticsearchIndexer.
                get(es.getIndexName(), "1bf2b6aa27dd8b45c7db58875004b8cb27a78ced5200b4976b63e351ebbae5ececb86076d90e156a7cdea06cde9573ca",
                        "f4078910c3e73a192e3a82d205f3c0bdb749c4e7b23c1d05a622db0f07d7f0ededb335abdb62aef41ace5d3cdb9298bc");

        InputStream source = new SourceExtractor(new PropertiesProvider(stringProperties), true).getSource(project(es.getIndexName()), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source).length).isNotEqualTo(49779);
    }

    @Test
    public void test_get_source_for_embedded_doc_without_digest_project_name() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Map<String, Object> stringProperties = Map.of(
            "digestAlgorithm", Document.DEFAULT_DIGESTER.toString(),
            "digestProjectName", "",
            "defaultProject", es.getIndexName());
        ElasticsearchIndexer elasticsearchIndexer = indexDocument(stringProperties, path, stringProperties);

        Document attachedPdf = elasticsearchIndexer.
                get(es.getIndexName(), "754ea07d66c2ec23d2849b4d44f276a7ebe719e586c20d15c7b772dcd4a620b0117e7396b76496ed5c10a066bf19d907",
                        "c78925fb478426ccc4c5a7cb975bc0f35d4079cd8a55d7a340bdccb3a46379e4940daa198c0be0dfd247cde338194105");

        InputStream source = new SourceExtractor(new PropertiesProvider(stringProperties)).getSource(project(es.getIndexName()), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(49779);
    }

    @Test
    public void test_get_source_for_embedded_doc_with_digest_project_name_using_legacy_value() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Map<String, Object> stringProperties  = Map.of(
            "digestAlgorithm", Document.DEFAULT_DIGESTER.toString(),
            "digestProjectName", "local-datashare",
            "artifactDir", tmpDir.newFolder("local_mode").toString(),
            "mode", "LOCAL");
        ElasticsearchIndexer elasticsearchIndexer = indexDocument(stringProperties, path, Map.of("defaultProject", es.getIndexName(), "digestProjectName", "local-datashare"));

        Document attachedPdf = elasticsearchIndexer.
                get(es.getIndexName(), "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623",
                        "34ec4641c845234af66cfded88fed3ea92ee27da41e610d67eed0b9ba0c04ecf1cefae80d694050e29b8aadfd9cc7205");

        InputStream source = new SourceExtractor(new PropertiesProvider(stringProperties)).getSource(project(es.getIndexName()), attachedPdf);
        assertThat(source).isNotNull();
        assertThat(getBytes(source)).hasSize(49779);
    }

    @Test(expected = EmbeddedDocumentExtractor.ContentNotFoundException.class)
    public void test_not_get_source_for_embedded_doc_with_digest_project_name_using_legacy_value_in_server() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Map<String, Object> stringProperties  =  Map.of(
            "digestAlgorithm", Document.DEFAULT_DIGESTER.toString(),
            "digestProjectName", "local-datashare",
            "defaultProject", es.getIndexName(),
            "artifactDir", tmpDir.newFolder("server_mode").toString(),
            "mode", "SERVER");
        ElasticsearchIndexer elasticsearchIndexer = indexDocument(stringProperties, path, Map.of("defaultProject", es.getIndexName(), "digestProjectName", "local-datashare"));

        Document attachedPdf = elasticsearchIndexer.
                get(es.getIndexName(), "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623",
                        "34ec4641c845234af66cfded88fed3ea92ee27da41e610d67eed0b9ba0c04ecf1cefae80d694050e29b8aadfd9cc7205");

        new SourceExtractor(new PropertiesProvider(stringProperties)).getSource(project(es.getIndexName()), attachedPdf);
    }

    private static ElasticsearchIndexer indexDocument(Map<String, Object> properties, Path path, Map<String, Object> spewerProperties) throws IOException {
        Options<String> options = Options.from(properties);
        DocumentFactory tikaFactory = new DocumentFactory().configure(options);
        Extractor extractor = new Extractor(tikaFactory, options);

        final TikaDocument document = extractor.extract(path);
        ElasticsearchIndexer indexer = createIndexer(es.getIndexName());
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer,
                new MemoryDocumentCollectionFactory<>(), l -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(spewerProperties));
        spewer.write(document);
        return indexer;
    }

    private static ElasticsearchIndexer createIndexer(String defaultProject) {
        return new ElasticsearchIndexer(es.client, new PropertiesProvider(Map.of("defaultProject", defaultProject))).withRefresh(Refresh.True);
    }

    @Test
    public void test_extract_embeds_for_doc() throws Exception {
        Document document = DocumentBuilder.createDoc(project(es.getIndexName()),get(getClass().getResource("/docs/embedded_doc.eml").getPath()))
                .with("it has been parsed")
                .with(Language.FRENCH)
                .with(Charset.defaultCharset())
                .ofContentType("message/rfc822")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .withContentLength(45L).build();

        new SourceExtractor(getPropertiesProvider()).extractEmbeddedSources(project(es.getIndexName()), document);
        assertThat(tmpDir.getRoot().toPath().resolve(es.getIndexName()).toFile()).isDirectory();
        assertThat(tmpDir.getRoot().toPath().resolve(es.getIndexName()).toFile().listFiles()).containsOnly(
                tmpDir.getRoot().toPath().resolve(es.getIndexName()).resolve("1b").toFile());
    }

    @Test
    public void test_extract_embeds_for_doc_with_no_digest_project_opt() throws Exception {
        Document document = DocumentBuilder.createDoc(project(es.getIndexName()),get(getClass().getResource("/docs/embedded_doc.eml").getPath()))
                .with("it has been parsed")
                .with(Language.FRENCH)
                .with(Charset.defaultCharset())
                .ofContentType("message/rfc822")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .withContentLength(45L).build();

        new SourceExtractor(getPropertiesProvider(true)).extractEmbeddedSources(project(es.getIndexName()), document);
        assertThat(tmpDir.getRoot().toPath().resolve(es.getIndexName()).toFile().listFiles()).containsOnly(
                tmpDir.getRoot().toPath().resolve(es.getIndexName()).resolve("75").toFile());
    }

    private byte[] getBytes(InputStream source) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nbTmpBytesRead;
        for(byte[] tmp = new byte[8192]; (nbTmpBytesRead = source.read(tmp)) > 0;) {
            buffer.write(tmp, 0, nbTmpBytesRead);
        }
        return buffer.toByteArray();
    }

    private PropertiesProvider getPropertiesProvider() {
        return getPropertiesProvider(false);
    }

    private PropertiesProvider getPropertiesProvider(boolean noDigestProject) {
        return new PropertiesProvider(Map.of(
                ARTIFACT_DIR_OPT, tmpDir.getRoot().toString(),
                NO_DIGEST_PROJECT_OPT, String.valueOf(noDigestProject))
        );
    }
}
