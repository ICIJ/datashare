package org.icij.datashare.io;

//import com.adobe.testing.s3mock.junit4.S3MockRule;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import software.amazon.awssdk.crt.Log;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;

import java.io.File;
import java.io.FileInputStream;

import static org.fest.assertions.Assertions.assertThat;

public class RemoteFilesTest {
    private static final String BUCKET_NAME = "mybucket";
    //@ClassRule
    // public static final S3MockRule S3_MOCK_RULE = S3MockRule.builder().silent().build();
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    //private final S3Client s3Client = S3_MOCK_RULE.createS3ClientV2();
    private final RemoteFiles remoteFiles = RemoteFiles.getWithEndPoint("https://localhost:12345");

    // @Before
    //public void setUp() {
      //  s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET_NAME).build());
    //}

    //@After
    //public void tearDown() {
      //  s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET_NAME).build());
    //}

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

    @Ignore("test for checking locally why a model is not synchronized")
    @Test
    public void test_integration() throws Exception {
        Log.initLoggingToFile(Log.LogLevel.Trace, "aws_logs.txt");
        RemoteFiles remoteFiles = RemoteFiles.getDefault();
        try (FileInputStream fis = new FileInputStream("/home/dev/src/datashare/dist/models/corenlp/4-5-5/fr/stanford-corenlp-4.5.5-models-fr.jar")) {
            System.out.println(DigestUtils.md5Hex(fis));
        }
        assertThat(remoteFiles.isSync("dist/models/corenlp/4-5-5/fr",
                new File("/home/dev/src/datashare/"))).isTrue();
    }
}
