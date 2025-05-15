package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.com.mail.Mail;
import org.icij.datashare.com.mail.MailException;
import org.icij.datashare.com.mail.MailSender;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ExtractException;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.datashare.utils.DocumentVerifier;
import org.icij.extract.extractor.EmbeddedDocumentExtractor.ContentNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.ZipException;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import org.icij.datashare.asynctasks.TaskGroupType;

@TaskGroup(TaskGroupType.Java)
public class BatchDownloadRunner extends DatashareTask<UriResult> implements Monitorable, UserTask, CancellableTask {
    private final static Logger logger = LoggerFactory.getLogger(BatchDownloadRunner.class);
    static final int MAX_SCROLL_SIZE = 3500;
    static final int MAX_BATCH_RESULT_SIZE = 10000;
    private final DocumentVerifier documentVerifier;
    private final Task task;
    volatile long docsToProcessSize = 0;
    private final AtomicInteger numberOfResults = new AtomicInteger(0);
    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final Function<Double, Void> progressCallback;
    private final Function<URI, MailSender> mailSenderSupplier;
    private final CountDownLatch callWaiterLatch;
    protected volatile boolean cancelAsked = false;
    protected volatile boolean requeueCancel;
    protected volatile Thread callThread;

    @Inject
    public BatchDownloadRunner(Indexer indexer, PropertiesProvider propertiesProvider, @Assisted Task task, @Assisted Function<Double, Void> progressCallback) {
        this(indexer, propertiesProvider, progressCallback, task, MailSender::new, new CountDownLatch(1));
    }

    BatchDownloadRunner(Indexer indexer, PropertiesProvider provider, Function<Double, Void> progressCallback, Task task, Function<URI, MailSender> mailSenderSupplier, CountDownLatch latch) {
        assert task.args.get("batchDownload") != null : "'batchDownload' property in task shouldn't be null";
        this.task = task;
        this.indexer = indexer;
        this.propertiesProvider = provider;
        this.progressCallback = progressCallback;
        this.mailSenderSupplier = mailSenderSupplier;
        this.documentVerifier = new DocumentVerifier(indexer, propertiesProvider);
        this.callWaiterLatch = latch;
    }

    @Override
    public UriResult runTask() throws Exception {
        int throttleMs = parseInt(propertiesProvider.get(BATCH_THROTTLE_OPT).orElse(DEFAULT_BATCH_THROTTLE));
        int maxResultSize = parseInt(propertiesProvider.get(BATCH_DOWNLOAD_MAX_NB_FILES_OPT).orElse(valueOf(MAX_BATCH_RESULT_SIZE)));
        String scrollDuration = propertiesProvider.get(BATCH_DOWNLOAD_SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        int scrollSizeFromParams = parseInt(propertiesProvider.get(BATCH_DOWNLOAD_SCROLL_SIZE_OPT)
                .orElse(propertiesProvider.get(SCROLL_SIZE_OPT)
                .orElse(valueOf(DEFAULT_SCROLL_SIZE))));
        int scrollSize = min(scrollSizeFromParams, MAX_SCROLL_SIZE);
        long maxZipSizeBytes = HumanReadableSize.parse(propertiesProvider.get(BATCH_DOWNLOAD_MAX_SIZE_OPT).orElse(DEFAULT_BATCH_DOWNLOAD_MAX_SIZE));
        long zippedFilesSize = 0;
        callThread = Thread.currentThread();
        callWaiterLatch.countDown(); // for tests
        BatchDownload batchDownload = getBatchDownload();

        logger.info("running batch download for user {} on project {} with {} scroll with throttle {}ms and scroll size of {}",
                batchDownload.user.getId(), batchDownload.projects, scrollDuration, throttleMs, scrollSize);
        Indexer.Searcher searcher = indexer.search(batchDownload.projects.stream().map(Project::getId).collect(toList()),
                Document.class, batchDownload.query).withoutSource("content").limit(scrollSize);

        try {
            List<? extends Entity> docsToProcess = searcher.scroll(scrollDuration).collect(toList());
            if (docsToProcess.isEmpty()) {
                logger.warn("no results for batchDownload {}", batchDownload.uuid);
                return null;
            }
            docsToProcessSize = searcher.totalHits();
            if (docsToProcessSize > maxResultSize) {
                logger.warn("number of results for batch download > {} for {}/{} (nb zip entries will be limited)",
                        maxResultSize, batchDownload.uuid, batchDownload.user);
            }

            logger.info("creating zip file with max input files size of {} bytes", maxZipSizeBytes);
            try (Zipper zipper = createZipper(batchDownload, propertiesProvider, mailSenderSupplier)) {
                HashMap<String, Object> taskProperties = new HashMap<>();
                taskProperties.put("batchDownload", batchDownload);
                while (!docsToProcess.isEmpty()) {
                    for (int i = 0; i < docsToProcess.size() && numberOfResults.get() < maxResultSize && zippedFilesSize <= maxZipSizeBytes; i++) {
                        if (cancelAsked) {
                            logger.info("cancelling batch download {} requeue={}", batchDownload.uuid, requeueCancel);
                            throw new CancelException(requeueCancel);
                        }
                        Document document = (Document) docsToProcess.get(i);
                        int addedBytes = documentVerifier.isRootDocumentSizeAllowed(document) ? zipper.add(document) : 0;
                        if (addedBytes > 0) {
                            zippedFilesSize += addedBytes;
                            numberOfResults.incrementAndGet();
                            progressCallback.apply(getProgressRate());
                        }
                    }
                    docsToProcess = searcher.scroll(scrollDuration).collect(toList());
                }
            }
        } catch (ElasticsearchException esEx) {
            throw ElasticSearchAdapterException.createFrom(esEx);
        }
        UriResult result = new UriResult(batchDownload.filename.toUri(), Files.size(batchDownload.filename));
        logger.info("created batch download file {} of {} entries for user {}", result, numberOfResults.get(), batchDownload.user.getId());
        return result;
    }

    private Zipper createZipper(BatchDownload batchDownload, PropertiesProvider propertiesProvider, Function<URI, MailSender> mailSenderSupplier) throws URISyntaxException, IOException {
        if (batchDownload.encrypted) {
            String rootHost = propertiesProvider.get("rootHost").orElse(null);
            URI mailSenderUri = new URI(propertiesProvider.get("smtpUrl").orElse("smtp://localhost:25"));
            MailSender mailSender = mailSenderSupplier.apply(mailSenderUri);
            return new ZipperWithPassword(batchDownload, propertiesProvider, mailSender, rootHost);
        }
        return new Zipper(batchDownload, propertiesProvider);
    }

    @Override
    public double getProgressRate() {
        return docsToProcessSize == 0 ? 0 : (double) numberOfResults.get() / docsToProcessSize;
    }

    @Override
    public User getUser() {
        return getBatchDownload().user;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + getBatchDownload().uuid;
    }

    private BatchDownload getBatchDownload() {
        return (BatchDownload) task.args.get("batchDownload");
    }

    @Override
    public void cancel(boolean requeue) {
        requeueCancel = requeue;
        cancelAsked = true;
        try {
            if (callThread != null) callThread.join();
        } catch (InterruptedException e) {
            logger.warn("batch download interrupted during cancel check status for {}", task.id);
        }
    }

    private static class Zipper implements AutoCloseable {

        protected final BatchDownload batchDownload;
        protected final ZipOutputStream zipOutputStream;
        private final PropertiesProvider propertiesProvider;

        protected Zipper(BatchDownload batchDownload, PropertiesProvider propertiesProvider) throws IOException {
            this(batchDownload, propertiesProvider, new ZipOutputStream(new FileOutputStream(batchDownload.filename.toFile())));
        }

        protected Zipper(BatchDownload batchDownload,  PropertiesProvider propertiesProvider, ZipOutputStream zipOutputStream) {
            this.batchDownload = batchDownload;
            this.zipOutputStream = zipOutputStream;
            this.propertiesProvider = propertiesProvider;
        }

        public int add(Document doc) throws IOException {
            try (InputStream from = new SourceExtractor(propertiesProvider).getSource(doc.getProject(), doc)) {
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
            } catch (ExtractException | ZipException | FileNotFoundException | ContentNotFoundException zex) {
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
        private final String rootHost;

        public ZipperWithPassword(BatchDownload batchDownload, PropertiesProvider propertiesProvider, MailSender mailSender, String rootHost) throws IOException {
            this(batchDownload, propertiesProvider, mailSender, RandomStringUtils.randomAlphanumeric(16), rootHost);
        }

        public ZipperWithPassword(BatchDownload batchDownload, PropertiesProvider propertiesProvider, MailSender mailSender, String password, String rootHost) throws IOException {
            super(batchDownload, propertiesProvider, new ZipOutputStream(new FileOutputStream(batchDownload.filename.toFile()), password.toCharArray()));
            this.password = password;
            this.passwordSender = mailSender;
            this.rootHost = rootHost;
        }

        public String batchDownloadsLink() {
            return StringUtils.stripEnd(this.rootHost, "/").concat("/#/tasks/batch-download");
        }

        public String batchDownloadsLinkRow () {
            if (rootHost == null || rootHost.trim().isEmpty()) {
                return "";
            }
            return String.format("You can download your file at the following location: %s\n\n", batchDownloadsLink());
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
                String from = "engineering@icij.org";
                String recipient = batchDownload.user.email;
                String subject = String.format("[Datashare] Your batch download is ready - %s", batchDownload.filename.getFileName());
                String body = "Hello,\n\n"
                        .concat("Your requested batch download has been successfully processed and is now ready for your retrieval.\n\n")
                        .concat(this.batchDownloadsLinkRow())
                        .concat("In order to ensure the highest level of security, your batch download is password protected.\n\n")
                        .concat(String.format("To open the ZIP file, the password is \"%s\".\n\n", password))
                        .concat("We strongly recommend that you keep this password confidential. Do not share it with anyone and delete this email after you have successfully accessed your batch download.");
                Mail mail = new Mail(from, recipient, subject, body);
                passwordSender.send(mail);
            } catch (MailException mex) {
                logger.error("failed to send mail password" , mex);
            }
        }
    }
}
