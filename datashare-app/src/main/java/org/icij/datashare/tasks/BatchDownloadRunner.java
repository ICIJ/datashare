package org.icij.datashare.tasks;

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
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor.ContentNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipException;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.*;

public class BatchDownloadRunner implements Callable<FileResult>, Monitorable, UserTask {
    private final static Logger logger = LoggerFactory.getLogger(BatchDownloadRunner.class);
    static final int MAX_SCROLL_SIZE = 3500;
    static final int MAX_BATCH_RESULT_SIZE = 10000;
    private final DocumentVerifier documentVerifier;
    private final TaskView<File> task;
    volatile long docsToProcessSize = 0;
    private final AtomicInteger numberOfResults = new AtomicInteger(0);
    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final BiFunction<String, Double, Void> progressCallback;
    private final Function<URI, MailSender> mailSenderSupplier;

    @Inject
    public BatchDownloadRunner(Indexer indexer, PropertiesProvider propertiesProvider, @Assisted TaskView<?> task, @Assisted BiFunction<String, Double, Void> progressCallback) {
        this(indexer, propertiesProvider, progressCallback, task, MailSender::new);
    }

    BatchDownloadRunner(Indexer indexer, PropertiesProvider provider, BiFunction<String, Double, Void> progressCallback, TaskView<?> task, Function<URI, MailSender> mailSenderSupplier) {
        assert task.properties.get("batchDownload") != null : "'batchDownload' property in task shouldn't be null";
        this.task = (TaskView<File>) task;
        this.indexer = indexer;
        this.propertiesProvider = provider;
        this.progressCallback = progressCallback;
        this.mailSenderSupplier = mailSenderSupplier;
        this.documentVerifier = new DocumentVerifier(indexer, propertiesProvider);
    }

    @Override
    public FileResult call() throws Exception {
        int throttleMs = parseInt(propertiesProvider.get(BATCH_THROTTLE_OPT).orElse(DEFAULT_BATCH_THROTTLE));
        int maxResultSize = parseInt(propertiesProvider.get(BATCH_DOWNLOAD_MAX_NB_FILES_OPT).orElse(valueOf(MAX_BATCH_RESULT_SIZE)));
        int scrollSizeFromParams = parseInt(propertiesProvider.get(BATCH_DOWNLOAD_SCROLL_SIZE_OPT)
                .orElse(propertiesProvider.get(SCROLL_SIZE_OPT)
                .orElse(valueOf(DEFAULT_SCROLL_SIZE))));
        int scrollSize = min(scrollSizeFromParams, MAX_SCROLL_SIZE);
        long maxZipSizeBytes = HumanReadableSize.parse(propertiesProvider.get(BATCH_DOWNLOAD_MAX_SIZE_OPT).orElse(DEFAULT_BATCH_DOWNLOAD_MAX_SIZE));
        long zippedFilesSize = 0;
        BatchDownload batchDownload = getBatchDownload();

        logger.info("running batch download for user {} on project {} with throttle {}ms and scroll size of {}",
                batchDownload.user.getId(), batchDownload.projects, throttleMs, scrollSize);
        Indexer.Searcher searcher = indexer.search(batchDownload.projects.stream().map(Project::getId).collect(toList()),
                Document.class, batchDownload.query).withoutSource("content").limit(scrollSize);

        List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
        if (docsToProcess.isEmpty()) {
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
            while (!docsToProcess.isEmpty()) {
                for (int i = 0; i < docsToProcess.size() && numberOfResults.get() < maxResultSize && zippedFilesSize <= maxZipSizeBytes; i++) {
                    Document document = (Document) docsToProcess.get(i);
                    int addedBytes = documentVerifier.isRootDocumentSizeAllowed(document) ? zipper.add(document) : 0;
                    if (addedBytes > 0) {
                        zippedFilesSize += addedBytes;
                        numberOfResults.incrementAndGet();
                        progressCallback.apply(task.id, getProgressRate());
                    }
                }
                docsToProcess = searcher.scroll().collect(toList());
            }
        }
        FileResult result = new FileResult(batchDownload.filename.toFile(), Files.size(batchDownload.filename));
        logger.info("created batch download file {} of {} entries for user {}", result, numberOfResults.get(), batchDownload.user.getId());
        return result;
    }

    private Zipper createZipper(BatchDownload batchDownload, PropertiesProvider propertiesProvider, Function<URI, MailSender> mailSenderSupplier) throws URISyntaxException, IOException {
        if (batchDownload.encrypted) {
            String rootHost = propertiesProvider.get("rootHost").orElse(null);
            URI mailSenderUri = new URI(propertiesProvider.get("smtpUrl").orElse("smtp://localhost:25"));
            MailSender mailSender = mailSenderSupplier.apply(mailSenderUri);
            return new ZipperWithPassword(batchDownload, mailSender, rootHost);
        }
        return new Zipper(batchDownload);
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
        return (BatchDownload) task.properties.get("batchDownload");
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
            try (InputStream from = new SourceExtractor().getSource(doc.getProject(), doc)) {
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

        public ZipperWithPassword(BatchDownload batchDownload, MailSender mailSender, String rootHost) throws IOException {
            this(batchDownload, mailSender, RandomStringUtils.randomAlphanumeric(16), rootHost);
        }

        public ZipperWithPassword(BatchDownload batchDownload, MailSender mailSender, String password, String rootHost) throws IOException {
            super(batchDownload, new ZipOutputStream(new FileOutputStream(batchDownload.filename.toFile()), password.toCharArray()));
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
