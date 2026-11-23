package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
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
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewerFactory;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.fest.assertions.Assertions.assertThat;

public class QRCodeTaskIntTest {
    private static final String QR_KEY = new FieldNames().forMetadata(QRCodeParserDecorator.QR_CODES.getName());
    private static final String QR_VALUE = "https://example.org/qr";
    private static final Function<Double, Void> NO_PROGRESS = progress -> null;

    @Rule public ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder corpus = new TemporaryFolder();

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

    private QRCodeTask qrCodeTask(Map<String, Object> extraArgs) {
        Map<String, Object> args = new HashMap<>(Map.of("defaultProject", es.getIndexName()));
        args.putAll(extraArgs);
        Task<Long> taskView = new Task<>(QRCodeTask.class.getName(), User.local(), args);
        return new QRCodeTask(stringQueueFactory, indexer, taskView, NO_PROGRESS);
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
