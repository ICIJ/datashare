package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.apache.commons.lang3.RandomStringUtils;
import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.com.mail.Mail;
import org.icij.datashare.com.mail.MailException;
import org.icij.datashare.com.mail.MailSender;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ExtractException;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor.ContentNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.ZipException;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.*;

public class BatchDownloadRunner implements Callable<File>, Monitorable, UserTask {
    private final static Logger logger = LoggerFactory.getLogger(BatchDownloadRunner.class);
    static final int MAX_SCROLL_SIZE = 3500;
    static final int MAX_BATCH_RESULT_SIZE = 10000;
    volatile long docsToProcessSize = 0;
    private final AtomicInteger numberOfResults = new AtomicInteger(0);

    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final BatchDownload batchDownload;
    private final Function<TaskView<File>, Void> updateCallback;
    private final Function<URI, MailSender> mailSenderSupplier;

    @Inject
    public BatchDownloadRunner(Indexer indexer, PropertiesProvider propertiesProvider, @Assisted BatchDownload batchDownload, @Assisted Function<TaskView<File>, Void> updateCallback) {
        this(indexer, propertiesProvider, batchDownload, updateCallback, MailSender::new);
    }

    BatchDownloadRunner(Indexer indexer, PropertiesProvider provider, BatchDownload batchDownload, Function<TaskView<File>, Void> updateCallback, Function<URI, MailSender> mailSenderSupplier) {
        this.indexer = indexer;
        this.propertiesProvider = provider;
        this.batchDownload = batchDownload;
        this.updateCallback = updateCallback;
        this.mailSenderSupplier = mailSenderSupplier;
    }

    @Override
    public File call() throws Exception {
        int throttleMs = parseInt(propertiesProvider.get(BATCH_THROTTLE).orElse("0"));
        int maxResultSize = parseInt(propertiesProvider.get(BATCH_DOWNLOAD_MAX_NB_FILES).orElse(valueOf(MAX_BATCH_RESULT_SIZE)));
        int scrollSize = min(parseInt(propertiesProvider.get(SCROLL_SIZE).orElse("1000")), MAX_SCROLL_SIZE);
        long maxZipSizeBytes = HumanReadableSize.parse(propertiesProvider.get(BATCH_DOWNLOAD_MAX_SIZE).orElse("100M"));
        long zippedFilesSize = 0;

        logger.info("running batch download for user {} on project {} with throttle {}ms and scroll size of {}",
                batchDownload.user.getId(), batchDownload.project, throttleMs, scrollSize);
        Indexer.Searcher searcher = indexer.search(batchDownload.project.getId(), Document.class).withoutSource("content").limit(scrollSize);
        if (batchDownload.isJsonQuery()) {
            searcher.set(batchDownload.queryAsJson());
        } else {
            searcher.with(batchDownload.query);
        }
        List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
        if (docsToProcess.size() == 0) {
            logger.warn("no results for batchDownload {}", batchDownload.uuid);
            return null;
        }
        docsToProcessSize = searcher.totalHits();
        if (docsToProcessSize > maxResultSize) {
            logger.warn("number of results for batch download > {} for {}/{} (nb zip entries will be limited)",
                    maxResultSize, batchDownload.uuid, batchDownload.user);
        }

        try (Zipper zipper = createZipper(batchDownload, propertiesProvider, mailSenderSupplier)) {
            HashMap<String, Object> taskProperties = new HashMap<>();
            taskProperties.put("batchDownload", batchDownload);
            while (docsToProcess.size() != 0) {
                for (int i = 0; i < docsToProcess.size() && numberOfResults.get() < maxResultSize && zippedFilesSize <= maxZipSizeBytes; i++) {
                    Entity doc = docsToProcess.get(i);
                    int addedBytes = zipper.add((Document) doc);
                    if (addedBytes > 0) {
                        zippedFilesSize += addedBytes;
                        numberOfResults.incrementAndGet();
                        updateCallback.apply(new TaskView<>(new MonitorableFutureTask<>(this, taskProperties)));
                    }
                }
                docsToProcess = searcher.scroll().collect(toList());
            }
        }
        logger.info("created batch download file {} ({} bytes/{} entries) for user {}",
                batchDownload.filename, Files.size(batchDownload.filename), numberOfResults, batchDownload.user.getId());
        return batchDownload.filename.toFile();
    }

    private Zipper createZipper(BatchDownload batchDownload, PropertiesProvider propertiesProvider, Function<URI, MailSender> mailSenderSupplier) throws URISyntaxException, IOException {
        return parseBoolean(propertiesProvider.get("batchDownloadEncrypt").orElse("false")) ?
                new ZipperWithPassword(batchDownload, mailSenderSupplier.apply(new URI(propertiesProvider.get("smtpUrl").orElse("smtp://localhost:25")))):
                new Zipper(batchDownload);
    }

    @Override
    public double getProgressRate() {
        return docsToProcessSize == 0 ? 0 : (double) numberOfResults.get() / docsToProcessSize;
    }

    @Override
    public User getUser() {
        return batchDownload.user;
    }

    @Override
    public String toString() {
        return getClass().getName() + "@" + batchDownload.uuid;
    }

    private static class Zipper implements AutoCloseable {
        protected final BatchDownload batchDownload;
        protected final ZipOutputStream zipOutputStream;

        protected Zipper(BatchDownload batchDownload) throws IOException {
            this(batchDownload, new ZipOutputStream(new FileOutputStream(batchDownload.filename.toFile())));
        }

        protected Zipper(BatchDownload batchDownload, ZipOutputStream zipOutputStream) {
            this.batchDownload = batchDownload;
            this.zipOutputStream = zipOutputStream;
        }

        public int add(Document doc) throws IOException {
            try (InputStream from = new SourceExtractor().getSource(batchDownload.project, doc)) {
                int zippedSize = 0;
                zipOutputStream.putNextEntry(createEntry(getEntryName(doc)));
                byte[] buffer = new byte[4096];
                int len;
                while ((len = from.read(buffer)) > 0) {
                    zipOutputStream.write(buffer, 0, len);
                    zippedSize += len;
                }
                zipOutputStream.closeEntry();
                return zippedSize;
            } catch (ExtractException|ZipException|ContentNotFoundException zex) {
                logger.warn("exception during extract/zip. skipping entry for doc " + doc.getId(), zex);
                return 0;
            }
        }

        protected ZipParameters createEntry(String entryName) {
            ZipParameters zipParams = new ZipParameters();
            zipParams.setFileNameInZip(entryName);
            return zipParams;
        }

        @NotNull
        private String getEntryName(Document doc) {
            return doc.getPath().isAbsolute() ? doc.getPath().toString().substring(1) : doc.getPath().toString();
        }

        @Override
        public void close() throws Exception {
            zipOutputStream.close();
        }
    }

    private static class ZipperWithPassword extends Zipper {
        private final String password;
        private final MailSender passwordSender;

        public ZipperWithPassword(BatchDownload batchDownload, MailSender mailSender) throws IOException {
            this(batchDownload, mailSender, RandomStringUtils.randomAlphanumeric(16));
        }

        public ZipperWithPassword(BatchDownload batchDownload, MailSender mailSender, String password) throws IOException {
            super(batchDownload, new ZipOutputStream(new FileOutputStream(batchDownload.filename.toFile()), password.toCharArray()));
            this.password = password;
            this.passwordSender = mailSender;
        }

        @Override
        protected ZipParameters createEntry(String entryName) {
            ZipParameters entry = super.createEntry(entryName);
            entry.setEncryptFiles(true);
            entry.setEncryptionMethod(EncryptionMethod.AES);
            return entry;
        }

        @Override
        public void close() throws Exception {
            zipOutputStream.close();
            try {
                passwordSender.send(new Mail("engineering@icij.org", batchDownload.user.email, String.format("[datashare] %s", batchDownload.filename.getFileName()),
                        "Your password to open the zip file is " + password));
            } catch (MailException mex) {
                logger.error("failed to send mail password" , mex);
            }
        }
    }
}
