package org.icij.datashare.cli;

import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class RemoteFiles {
    private final AmazonS3 s3Client;
    private final String bucket;

    public RemoteFiles(final AmazonS3 s3Client, final String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public void upload(final String key, final File file) throws FileNotFoundException {
        final ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.length());
        s3Client.putObject(new PutObjectRequest(bucket, key, new FileInputStream(file), metadata));
    }

    public void upload(File localFile, String remoteKey) throws InterruptedException {
        TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(this.s3Client).build();
        final MultipleFileUpload uploads = transferManager.uploadDirectory(
                bucket, remoteKey, localFile, true);

        for (Upload upload : uploads.getSubTransfers()) {
            upload.waitForUploadResult();
        }
        transferManager.shutdownNow();
    }

    public void download(String remoteKey, File localFile) throws InterruptedException, IOException {
        if (localFile.isDirectory()) {
            TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(this.s3Client).build();
            transferManager.downloadDirectory(bucket, remoteKey, localFile).waitForCompletion();
            transferManager.shutdownNow();
        } else {
            final S3Object s3Object = s3Client.getObject(this.bucket, remoteKey);
            Files.copy(s3Object.getObjectContent(), Paths.get(localFile.getPath()));
        }
    }

    public boolean objectExists(final String key) {
        return s3Client.doesObjectExist(this.bucket, key);
    }

    public static void main(String[] args) throws InterruptedException {
        AmazonS3 amazonS3 = AmazonS3ClientBuilder.standard().withRegion("us-east-1")
                .withCredentials(new ClasspathPropertiesFileCredentialsProvider("s3.properties")).build();
        final RemoteFiles remoteFiles = new RemoteFiles(amazonS3, "s3.datashare.icij.org");

        for (String arg : args) {
            remoteFiles.upload(new File(arg), "");
        }
    }
}

