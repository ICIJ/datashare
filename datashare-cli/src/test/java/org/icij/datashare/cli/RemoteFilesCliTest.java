package org.icij.datashare.cli;

import com.adobe.testing.s3mock.S3MockRule;
import com.amazonaws.services.s3.AmazonS3;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.RemoteFilesCli.S3_DATASHARE_BUCKET_NAME;

public class RemoteFilesCliTest {
    @ClassRule
    public static S3MockRule S3_MOCK_RULE = new S3MockRule();
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    private final AmazonS3 s3Client = S3_MOCK_RULE.createS3Client();

    @Before
    public void setUp() {
        RemoteFilesCli.setS3client(s3Client);
        s3Client.createBucket(S3_DATASHARE_BUCKET_NAME);
    }

    @After
    public void tearDown() {
        s3Client.deleteBucket(S3_DATASHARE_BUCKET_NAME);
    }

    @Test
    public void test_main_upload_download_one_file() throws Exception {
        RemoteFilesCli.main(new String[]{"-u", "-f", "src/test/resources/sampleFile.txt"});
        RemoteFilesCli.main(new String[]{"-d", "-f", folder.getRoot().getPath() + "/foo.txt"});

        assertThat(new File(folder.getRoot().getPath()+ "/foo.txt")).exists();
    }

    @Test
    public void test_main_upload_download_one_directory() throws Exception {
        RemoteFilesCli.main(new String[]{"-u", "-f", "src/test/resources/"});
        RemoteFilesCli.main(new String[]{"-d", "-f", folder.getRoot().getPath()});

        assertThat(new File(folder.getRoot().getPath()+ "/sampleFile.txt")).exists();
    }
}
