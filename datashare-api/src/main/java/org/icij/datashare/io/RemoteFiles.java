package org.icij.datashare.io;

import com.amazonaws.ClientConfiguration;
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
    public static final String S3_DATASHARE_BUCKET_NAME = "s3.datashare.icij.org";
    private static final String S3_REGION = "us-east-1";
    private static final int READ_TIMEOUT = 120;
    private static final int CONNECTION_TIMEOUT = 30;
    private final AmazonS3 s3Client;
    private final String bucket;

    public RemoteFiles(final AmazonS3 s3Client, final String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public static RemoteFiles getDefault() {
        ClientConfiguration config = new ClientConfiguration(); 
        config.setConnectionTimeout(CONNECTION_TIMEOUT);
        config.setSocketTimeout(READ_TIMEOUT); 
        return new RemoteFiles(AmazonS3ClientBuilder.standard().withRegion(S3_REGION)
                .withCredentials(new ClasspathPropertiesFileCredentialsProvider("s3.properties"))
                .withClientConfiguration(config).build(), S3_DATASHARE_BUCKET_NAME);
    }

    public void upload(final File localFile, final String remoteKey) throws InterruptedException, FileNotFoundException {
        if (localFile.isDirectory()) {
            TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(this.s3Client).build();
            final MultipleFileUpload uploads = transferManager.uploadDirectory(bucket, remoteKey, localFile, true);

            for (Upload upload : uploads.getSubTransfers()) {
                upload.waitForUploadResult();
            }
            transferManager.shutdownNow(false);
        } else {
            final ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(localFile.length());
            s3Client.putObject(new PutObjectRequest(bucket, remoteKey, new FileInputStream(localFile), metadata));
        }
    }

    public void download(final String remoteKey, final File localFile) throws InterruptedException, IOException {
        if (localFile.isDirectory()) {
            TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(this.s3Client).build();
            transferManager.downloadDirectory(bucket, remoteKey, localFile).waitForCompletion();
            transferManager.shutdownNow(false);
        } else {
            final S3Object s3Object = s3Client.getObject(this.bucket, remoteKey);
            Files.copy(s3Object.getObjectContent(), Paths.get(localFile.getPath()));
        }
    }

    public boolean objectExists(final String key) {
        return s3Client.doesObjectExist(this.bucket, key);
    }
}

