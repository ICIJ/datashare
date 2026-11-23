package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.nodes.NodesStatsResponse;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewerFactory;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.extract.parser.QRCodeParserDecorator;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class QRCodeTaskIntTest {
    private static final String QR_KEY = new FieldNames().forMetadata(QRCodeParserDecorator.QR_CODES.getName());
    private static final String QR_VALUE = "https://example.org/qr";
    private static final Function<Double, Void> NO_PROGRESS = progress -> null;

    @Rule public ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder corpus = new TemporaryFolder();
    @Rule public TemporaryFolder attachments = new TemporaryFolder();
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();

    private final MemoryDocumentCollectionFactory<String> stringQueueFactory = new MemoryDocumentCollectionFactory<>();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final ElasticsearchIndexer indexer =
            new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    @Test
    public void test_append_lines_to_empty_content() {
        assertThat(QRCodeTask.appendLines("", List.of("a", "b"))).isEqualTo("QR code: a\nQR code: b\n");
    }

    @Test
    public void test_append_lines_after_text() {
        assertThat(QRCodeTask.appendLines("hello", List.of("a"))).isEqualTo("hello\nQR code: a\n");
        assertThat(QRCodeTask.appendLines("hello\n", List.of("a"))).isEqualTo("hello\nQR code: a\n");
    }

    @Test(timeout = 120_000)
    public void test_appends_the_value_to_a_standalone_image() throws Exception {
        Path qr = png(corpus.getRoot().toPath().resolve("qr.png"), QR_VALUE);
        Path plain = plainPng(corpus.getRoot().toPath().resolve("plain.png"));
        index(Map.of(), qr, plain);
        Document qrBefore = documentAt(qr);
        Document plainBefore = documentAt(plain);

        qrCodeTask(Map.of()).call();

        Document qrAfter = documentAt(qr);
        Document plainAfter = documentAt(plain);
        assertThat(qrAfter.getContent()).endsWith("QR code: " + QR_VALUE + "\n");
        assertThat(qrAfter.getContentTextLength()).isEqualTo(qrAfter.getContent().length());
        assertThat(qrAfter.getMetadata().get(QR_KEY)).isEqualTo(List.of(QR_VALUE));
        assertThat(qrAfter.getContentLength()).isEqualTo(qrBefore.getContentLength());
        assertThat(plainAfter.getMetadata().containsKey(QR_KEY)).isFalse();
        assertThat(plainAfter.getContent()).isEqualTo(plainBefore.getContent());
    }

    @Test(timeout = 120_000)
    public void test_a_second_run_changes_nothing() throws Exception {
        Path qr = png(corpus.getRoot().toPath().resolve("qr.png"), QR_VALUE);
        Path plain = plainPng(corpus.getRoot().toPath().resolve("plain.png"));
        index(Map.of(), qr, plain);

        qrCodeTask(Map.of()).call();
        Long secondRun = qrCodeTask(Map.of()).call();

        assertThat(qrLines(documentAt(qr))).isEqualTo(1);
        assertThat(secondRun).isEqualTo(1L);
    }

    @Test(timeout = 120_000)
    public void test_skips_a_document_already_decoded_at_index_time() throws Exception {
        Path qr = png(corpus.getRoot().toPath().resolve("qr.png"), QR_VALUE);
        index(Map.of("qrCodes", "true"), qr);
        String contentBefore = documentAt(qr).getContent();

        qrCodeTask(Map.of()).call();

        Document after = documentAt(qr);
        assertThat(after.getContent()).isEqualTo(contentBefore);
        assertThat(qrLines(after)).isEqualTo(1);
    }

    @Test(timeout = 120_000)
    public void test_a_missing_file_does_not_stop_the_stage() throws Exception {
        Path qr = png(corpus.getRoot().toPath().resolve("qr.png"), QR_VALUE);
        Path moved = png(corpus.getRoot().toPath().resolve("moved.png"), "https://example.org/moved");
        index(Map.of(), qr, moved);
        Document movedBefore = documentAt(moved);
        Files.delete(moved);

        qrCodeTask(Map.of()).call();

        assertThat(documentAt(qr).getContent()).endsWith("QR code: " + QR_VALUE + "\n");
        assertThat(documentAt(moved).getMetadata().containsKey(QR_KEY)).isFalse();
        assertThat(documentAt(moved).getContent()).isEqualTo(movedBefore.getContent());
    }

    @Test(timeout = 120_000)
    public void test_parses_an_uncached_root_once_for_all_its_images() throws Exception {
        Path mail = mailWithTwoCodes("mail.eml", "one", "two");
        index(Map.of(), mail);
        SourceExtractor sourceExtractor = spy(new SourceExtractor(new PropertiesProvider()));

        qrCodeTask(Map.of(), sourceExtractor).call();

        assertDecoded("one");
        assertDecoded("two");
        verify(sourceExtractor, times(1)).forEachEmbed(any(), any(), any());
    }

    @Test(timeout = 120_000)
    public void test_groups_a_root_split_across_scroll_pages() throws Exception {
        Path mail = mailWithTwoCodes("mail.eml", "one", "two");
        index(Map.of(), mail);
        SourceExtractor sourceExtractor = spy(new SourceExtractor(new PropertiesProvider()));

        qrCodeTask(Map.of("scrollSize", "1"), sourceExtractor).call();

        assertDecoded("one");
        assertDecoded("two");
        verify(sourceExtractor, times(1)).forEachEmbed(any(), any(), any());
    }

    @Test(timeout = 120_000)
    public void test_reads_cached_embeds_without_parsing_the_root() throws Exception {
        Path mail = mailWithTwoCodes("mail.eml", "one", "two");
        Map<String, Object> artifactArgs = Map.of("artifacts", "raw", "artifactDir", artifactDir.getRoot().toString());
        index(artifactArgs, mail);
        SourceExtractor sourceExtractor = spy(new SourceExtractor(new PropertiesProvider(new HashMap<>(artifactArgs))));

        qrCodeTask(artifactArgs, sourceExtractor).call();

        assertDecoded("one");
        assertDecoded("two");
        verify(sourceExtractor, never()).forEachEmbed(any(), any(), any());
    }

    @Test(timeout = 120_000)
    public void test_a_root_that_fails_to_parse_does_not_stop_the_stage() throws Exception {
        Path broken = mailWithTwoCodes("a.eml", "one", "two");
        Path mail = mailWithTwoCodes("b.eml", "three", "four");
        index(Map.of(), broken, mail);
        Files.write(broken, new byte[0]);
        SourceExtractor sourceExtractor = spy(new SourceExtractor(new PropertiesProvider()));

        qrCodeTask(Map.of(), sourceExtractor).call();

        assertDecoded("three");
        assertDecoded("four");
    }

    @Test(timeout = 120_000)
    public void test_an_embed_the_walk_never_yields_counts_as_failed() throws Exception {
        Path mail = mailWithTwoCodes("mail.eml", "one", "two");
        index(Map.of(), mail);
        SourceExtractor sourceExtractor = spy(new SourceExtractor(new PropertiesProvider()));
        doNothing().when(sourceExtractor).forEachEmbed(any(), any(), any());

        Long processed = qrCodeTask(Map.of(), sourceExtractor).call();

        assertThat(processed).isEqualTo(0L);
        assertThat(documentsWithMetadataKey()).isEmpty();
    }

    @Test(timeout = 120_000)
    public void test_holds_no_scroll_context_while_walking_a_root() throws Exception {
        Path first = mailWithTwoCodes("a.eml", "one", "two");
        Path second = mailWithTwoCodes("b.eml", "three", "four");
        index(Map.of(), first, second);
        SourceExtractor sourceExtractor = spy(new SourceExtractor(new PropertiesProvider()));
        List<Long> openContextsDuringWalk = new ArrayList<>();
        doAnswer(invocation -> {
            openContextsDuringWalk.add(openSearchContexts());
            return invocation.callRealMethod();
        }).when(sourceExtractor).forEachEmbed(any(), any(), any());

        qrCodeTask(Map.of(), sourceExtractor).call();

        assertThat(openContextsDuringWalk).isEqualTo(List.of(0L, 0L));
        assertDecoded("one");
        assertDecoded("three");
    }

    @Test(timeout = 120_000)
    public void test_an_index_error_on_one_document_does_not_stop_the_stage() throws Exception {
        Path rejected = png(corpus.getRoot().toPath().resolve("rejected.png"), "https://example.org/rejected");
        Path qr = png(corpus.getRoot().toPath().resolve("qr.png"), QR_VALUE);
        index(Map.of(), rejected, qr);
        String rejectedId = documentAt(rejected).getId();
        ElasticsearchIndexer failingIndexer = spy(indexer);
        doThrow(new RuntimeException("es_rejected_execution_exception")).when(failingIndexer)
                .update(eq(es.getIndexName()), eq(rejectedId), anyMap(), anyString());
        SourceExtractor sourceExtractor = new SourceExtractor(new PropertiesProvider());

        qrCodeTask(failingIndexer, Map.of(), sourceExtractor).call();

        assertThat(documentAt(qr).getContent()).endsWith("QR code: " + QR_VALUE + "\n");
        assertThat(documentAt(rejected).getMetadata().containsKey(QR_KEY)).isFalse();
    }

    private QRCodeTask qrCodeTask(Map<String, Object> extraArgs) {
        Map<String, Object> args = new HashMap<>(Map.of("defaultProject", es.getIndexName()));
        args.putAll(extraArgs);
        Task<Long> taskView = new Task<>(QRCodeTask.class.getName(), User.local(), args);
        return new QRCodeTask(stringQueueFactory, indexer, taskView, NO_PROGRESS);
    }

    private QRCodeTask qrCodeTask(Map<String, Object> extraArgs, SourceExtractor sourceExtractor) {
        return qrCodeTask(indexer, extraArgs, sourceExtractor);
    }

    private QRCodeTask qrCodeTask(Indexer taskIndexer, Map<String, Object> extraArgs, SourceExtractor sourceExtractor) {
        Map<String, Object> args = new HashMap<>(Map.of("defaultProject", es.getIndexName()));
        args.putAll(extraArgs);
        Task<Long> taskView = new Task<>(QRCodeTask.class.getName(), User.local(), args);
        return new QRCodeTask(stringQueueFactory, taskIndexer, sourceExtractor, taskView, NO_PROGRESS);
    }

    private void assertDecoded(String value) throws IOException {
        List<Document> decoded = documentsWithMetadataKey().stream()
                                                           .filter(doc -> doc.getMetadata().get(QR_KEY).equals(List.of(value)))
                                                           .toList();
        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0).getContent()).endsWith("QR code: " + value + "\n");
        assertThat(decoded.get(0).isRootDocument()).isFalse();
    }

    private long openSearchContexts() throws IOException {
        NodesStatsResponse stats = es.client.nodes().stats(request -> request.metric("indices"));
        return stats.nodes().values().stream().mapToLong(node -> node.indices().search().openContexts()).sum();
    }

    private List<Document> documentsWithMetadataKey() throws IOException {
        return indexer.search(List.of(es.getIndexName()), Document.class).execute()
                      .map(Document.class::cast)
                      .filter(doc -> doc.getMetadata() != null && doc.getMetadata().containsKey(QR_KEY))
                      .toList();
    }

    private Path mailWithTwoCodes(String name, String first, String second) throws Exception {
        Path firstPng = png(attachments.getRoot().toPath().resolve(name + "-" + first + ".png"), first);
        Path secondPng = png(attachments.getRoot().toPath().resolve(name + "-" + second + ".png"), second);
        return eml(corpus.getRoot().toPath().resolve(name), firstPng, secondPng);
    }

    private static Path eml(Path file, Path... attachments) throws IOException {
        StringBuilder mime = new StringBuilder();
        mime.append("From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: codes\r\n");
        mime.append("MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"BOUNDARY\"\r\n\r\n");
        mime.append("--BOUNDARY\r\nContent-Type: text/plain\r\n\r\nsee attached\r\n");
        for (Path attachment : attachments) {
            byte[] bytes = Files.readAllBytes(attachment);
            String encoded = Base64.getMimeEncoder().encodeToString(bytes);
            String name = attachment.getFileName().toString();
            mime.append("--BOUNDARY\r\nContent-Type: image/png; name=\"").append(name).append("\"\r\n");
            mime.append("Content-Disposition: attachment; filename=\"").append(name).append("\"\r\n");
            mime.append("Content-Transfer-Encoding: base64\r\n\r\n").append(encoded).append("\r\n");
        }
        mime.append("--BOUNDARY--\r\n");
        Files.writeString(file, mime.toString());
        return file;
    }

    private void index(Map<String, Object> extraArgs, Path... files) throws Exception {
        Map<String, Object> args = new HashMap<>(Map.of(
                "defaultProject", es.getIndexName(),
                "queueName", "test:queue",
                "ocr", "false"));
        args.putAll(extraArgs);
        PropertiesProvider props = new PropertiesProvider(args);
        MemoryDocumentCollectionFactory<Path> pathQueueFactory = new MemoryDocumentCollectionFactory<>();
        String indexQueueName = new PipelineHelper(props).getQueueNameFor(Stage.INDEX);
        DocumentQueue<Path> indexQueue = pathQueueFactory.createQueue(indexQueueName, Path.class);
        List.of(files).forEach(indexQueue::add);
        ElasticsearchSpewerFactory spewerFactory = new ElasticsearchSpewerFactory(indexer, stringQueueFactory,
                text -> Language.ENGLISH, new FieldNames(), props);
        Task<Long> taskView = new Task<>(IndexTask.class.getName(), User.local(), args);
        new IndexTask(spewerFactory, pathQueueFactory, new UpstreamGate.Factory(taskRepository), taskView, null).call();
    }

    private Document documentAt(Path file) throws IOException {
        Map<Path, Document> byPath = indexer.search(List.of(es.getIndexName()), Document.class).execute()
                                            .map(Document.class::cast)
                                            .collect(Collectors.toMap(Document::getPath, doc -> doc));
        return byPath.get(file);
    }

    private static long qrLines(Document document) {
        return document.getContent().lines().filter(line -> line.startsWith("QR code: ")).count();
    }

    private static Path png(Path file, String value) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 200, 200);
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                int color = matrix.get(x, y) ? 0x000000 : 0xFFFFFF;
                image.setRGB(x, y, color);
            }
        }
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static Path plainPng(Path file) throws IOException {
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}
