package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.com.mail.Mail;
import org.icij.datashare.com.mail.MailSender;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadRunnerEncryptedIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder fs = new TemporaryFolder();

    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    @Mock TaskSupplier taskSupplier;

    @Test
    public void test_zip_with_password_should_encrypt_file_and_send_mail() throws Exception {
        new IndexerHelper(es.client).indexFile("mydoc.txt", "content", fs);
        BatchDownload batchDownload = createBatchDownload("*");
        MailSender mailSender = mock(MailSender.class);
        Task taskView =
            new Task(BatchDownloadRunner.class.getName(), batchDownload.user,
                    new HashMap<>() {{
                    put("batchDownload", batchDownload);
                }});
        new BatchDownloadRunner(indexer, createProvider(), taskView.progress(taskSupplier::progress), taskView, (uri) -> mailSender, new CountDownLatch(1)).call();

        assertThat(new net.lingala.zip4j.ZipFile(batchDownload.filename.toFile()).isEncrypted()).isTrue();
        ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(mailSender).send(mailCaptor.capture());
        assertThat(mailCaptor.getValue().from).isEqualTo("engineering@icij.org");
        assertThat(mailCaptor.getValue().toRecipientList).containsExactly("foo@bar.com");
        assertThat(mailCaptor.getValue().subject).isEqualTo("[Datashare] Your batch download is ready - " + batchDownload.filename.getFileName());
        assertThat(mailCaptor.getValue().messageBody).contains("https://datashare-demo.icij.org/#/tasks/batch-download");
    }

    @NotNull
    private BatchDownload createBatchDownload(String query) {
        return new BatchDownload(singletonList(project(TEST_INDEX)), new User("foo", "bar", "foo@bar.com"), query, null, fs.getRoot().toPath(), true);
    }

    @Before
    public void setUp() throws Exception { initMocks(this); }

    @After
    public void tearDown() throws IOException { es.removeAll();}

    @NotNull
    private PropertiesProvider createProvider() {
        return new PropertiesProvider(new HashMap<>() {{
            put("downloadFolder", fs.getRoot().toString());
            put("rootHost", "https://datashare-demo.icij.org");
        }});
    }
}
