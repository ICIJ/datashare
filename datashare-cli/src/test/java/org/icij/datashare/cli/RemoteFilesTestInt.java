package org.icij.datashare.cli;

import com.adobe.testing.s3mock.S3MockRule;
import com.amazonaws.services.s3.AmazonS3;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.fest.assertions.Assertions.assertThat;


public class RemoteFilesTestInt {
    private static final String BUCKET_NAME = "mybucket";
    @ClassRule
    public static S3MockRule S3_MOCK_RULE = new S3MockRule();
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final AmazonS3 s3Client = S3_MOCK_RULE.createS3Client();
    private RemoteFiles remoteFiles = new RemoteFiles(s3Client, BUCKET_NAME);

    @Before
    public void setUp() {
        s3Client.createBucket(BUCKET_NAME);
    }

    @After
    public void tearDown() {
        s3Client.deleteBucket(BUCKET_NAME);
    }

    @Test
    public void test_upload_download_file_with_key_at_root_directory() throws Exception {
        final String fileName = "file.txt";
        assertThat(remoteFiles.objectExists(fileName)).isFalse();

        remoteFiles.upload(new File("src/test/resources/sampleFile.txt"), fileName);
        remoteFiles.download(fileName, new File(folder.getRoot().getPath() + '/' + fileName));

        final File downloadedFile = new File(folder.getRoot().getPath() + '/' + fileName);
        assertThat(downloadedFile).exists();
        assertThat(downloadedFile).hasSameContentAs(new File("src/test/resources/sampleFile.txt"));
    }

    // TODO: uncomment when the bug in S3Mock will be solved :
    // cf https://github.com/adobe/S3Mock/issues/8

//    @Test
//    public void test_upload_download_directory() throws IOException, InterruptedException {
//        assertThat(remoteFiles.objectExists("prefix")).isFalse();
//
//        remoteFiles.upload(new File("src/test/resources"), "prefix");
//        remoteFiles.download("prefix", folder.getRoot());
//
//        assertThat(new File(folder.getRoot().getPath() + "/prefix/sampleFile.txt")).exists();
//    }
//
//    @Test
//    public void test_upload_file_with_key_in_sub_directory() throws FileNotFoundException {
//        final String filePath = "path/to/a/file.txt";
//        remoteFiles.upload(filePath, new File("src/test/resources/sampleFile.txt"));
//
//        assertThat(remoteFiles.objectExists(filePath)).isTrue();
//    }

    @Test
    public void test_main_upload_download_one_file() throws Exception {
        RemoteFiles.main(new String[] {"-u", "-f", "src/test/resources/sampleFile.txt"});
        RemoteFiles.main(new String[] {"-d", "-f", folder.getRoot().getPath() + "/foo.txt"});

        assertThat(new File(folder.getRoot().getPath()+ "/foo.txt")).exists();
    }

    @Test
    public void test_main_upload_download_one_directory() throws Exception {
        RemoteFiles.main(new String[] {"-u", "-f", "src/test/resources/"});
        RemoteFiles.main(new String[] {"-d", "-f", folder.getRoot().getPath()});

        assertThat(new File(folder.getRoot().getPath()+ "/sampleFile.txt")).exists();
    }
}
