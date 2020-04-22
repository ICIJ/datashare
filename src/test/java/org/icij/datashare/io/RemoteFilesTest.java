package org.icij.datashare.io;

import com.adobe.testing.s3mock.S3MockRule;
import com.amazonaws.services.s3.AmazonS3;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.fest.assertions.Assertions.assertThat;

public class RemoteFilesTest {
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

    @Test
    public void test_upload_file_with_key_in_sub_directory() throws Exception {
        final String filePath = "path/to/a/file.txt";
        remoteFiles.upload(new File("src/test/resources/sampleFile.txt"), filePath);

        assertThat(remoteFiles.objectExists(filePath)).isTrue();
    }

    @Test
    public void test_upload_download_directory() throws Exception {
        assertThat(remoteFiles.objectExists("prefix")).isFalse();

        remoteFiles.upload(new File("src/test/resources"), "prefix");
        remoteFiles.download("prefix", folder.getRoot());

        assertThat(new File(folder.getRoot().getPath() + "/prefix/sampleFile.txt")).exists();
    }

    @Test
    public void test_check_simple_file_integrity() throws Exception {
        remoteFiles.upload(new File("src/test/resources"), "prefix");
        File file = new File(folder.getRoot().getPath() + "/prefix/sampleFile.txt");
        file.getParentFile().mkdirs();
        file.createNewFile();

        assertThat(remoteFiles.isSync("prefix/sampleFile.txt", file)).isFalse();
        remoteFiles.download("prefix", folder.getRoot());
        assertThat(remoteFiles.isSync("prefix/sampleFile.txt", file)).isTrue();
    }

    @Test
    public void test_check_directory_integrity__file_names_and_sizes_should_be_the_same() throws Exception {
        assertThat(remoteFiles.isSync("prefix", folder.getRoot())).isFalse();

        remoteFiles.upload(new File("src/test/resources"), "prefix");
        File file = new File(folder.getRoot().getPath() + "/prefix/sampleFile.txt");
        file.getParentFile().mkdirs();
        assertThat(remoteFiles.isSync("prefix", folder.getRoot())).isFalse();

        file.createNewFile();
        new File(folder.getRoot().getPath() + "/prefix/datashare.properties").createNewFile();
        assertThat(remoteFiles.isSync("prefix", folder.getRoot())).isFalse();

        remoteFiles.download("prefix", folder.getRoot());
        assertThat(remoteFiles.isSync("prefix", folder.getRoot())).isTrue();
    }

    @Test
    public void test_check_directory_doesnt_compare_empty_files() throws Exception {
        File prefixDir = folder.getRoot().toPath().resolve("prefix").toFile();
        prefixDir.mkdir();
        File emptyFile = prefixDir.toPath().resolve("virtualdir").toFile();
        emptyFile.createNewFile();
        remoteFiles.upload(emptyFile, "prefix/virtualdir");
        emptyFile.delete();

        assertThat(remoteFiles.objectExists("prefix/virtualdir")).isTrue();

        assertThat(remoteFiles.isSync("prefix", folder.getRoot())).isTrue();
    }
}
