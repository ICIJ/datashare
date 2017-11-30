package org.icij.datashare.cli;

import com.adobe.testing.s3mock.S3MockRule;
import com.amazonaws.services.s3.AmazonS3;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;


public class RemoteFilesTest {
    private static final String BUCKET_NAME = "mybucket";
    @ClassRule
    public static S3MockRule S3_MOCK_RULE = new S3MockRule();
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final AmazonS3 s3Client = S3_MOCK_RULE.createS3Client();

    @Before
    public void setUp() {
        s3Client.createBucket(BUCKET_NAME);
    }
    @After
    public void tearDown() {
        s3Client.deleteBucket(BUCKET_NAME);
    }

    @Test
    public void test_upload_download_file_at_root_directory() throws IOException {
        final byte[] fileContent = {'c', 'o', 'n', 't', 'e', 'n', 't'};
        final String fileName = "file.txt";
        RemoteFiles remoteFiles = new RemoteFiles(s3Client, BUCKET_NAME);

        assertThat(remoteFiles.objectExists(fileName)).isFalse();

        remoteFiles.upload(fileName, new ByteArrayInputStream(fileContent));
        final File targetFolder = folder.newFolder();
        remoteFiles.download(fileName, targetFolder);

        byte[] content = Files.readAllBytes(Paths.get(targetFolder.getPath() + '/' + fileName));
        assertThat(content).contains(fileContent);
    }
}
