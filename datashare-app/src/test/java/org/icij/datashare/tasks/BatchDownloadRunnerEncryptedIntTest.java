package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
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

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.function.Function;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadRunnerEncryptedIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder fs = new TemporaryFolder();
    @Mock Function<TaskView<File>, Void> updateCallback;
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

    @Test
    public void test_zip_with_password_should_encrypt_file_and_send_mail() throws Exception {
        new IndexerHelper(es.client).indexFile("mydoc.txt", "content", fs);
        BatchDownload batchDownload = createBatchDownload("*");
        MailSender mailSender = mock(MailSender.class);
        new BatchDownloadRunner(indexer, createProvider(), batchDownload, updateCallback, (uri) -> mailSender).call();

        assertThat(new net.lingala.zip4j.ZipFile(batchDownload.filename.toFile()).isEncrypted()).isTrue();
        ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(mailSender).send(mailCaptor.capture());
        assertThat(mailCaptor.getValue().from).isEqualTo("engineering@icij.org");
        assertThat(mailCaptor.getValue().toRecipientList).containsExactly("foo@bar.com");
        assertThat(mailCaptor.getValue().subject).isEqualTo("[datashare] " + batchDownload.filename.getFileName());
    }

    @NotNull
    private BatchDownload createBatchDownload(String query) {
        return new BatchDownload(project(TEST_INDEX), new User("foo", "bar", "foo@bar.com"), query, fs.getRoot().toPath(), true);
    }

    @Before
    public void setUp() throws Exception { initMocks(this); }

    @After
    public void tearDown() throws IOException { es.removeAll();}

    @NotNull
    private PropertiesProvider createProvider() {
        return new PropertiesProvider(new HashMap<String, String>() {{
            put("downloadFolder", fs.getRoot().toString());
        }});
    }
}
