package org.icij.datashare.io;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static java.nio.file.Files.walk;
import static java.nio.file.Paths.get;
import static java.util.stream.Collectors.toMap;

public class RemoteFiles {
    private static final String S3_DATASHARE_BUCKET_NAME = "datashare-nlp";
    private static final String S3_DATASHARE_ENDPOINT = "s3-accelerate.amazonaws.com/";
    private static final String S3_REGION = "us-east-1";
    private static final int READ_TIMEOUT_MS = 120 * 1000;
    private static final int CONNECTION_TIMEOUT_MS = 30 * 1000;
    private final AmazonS3 s3Client;
    private final String bucket;

    RemoteFiles(final AmazonS3 s3Client, final String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public static RemoteFiles getDefault() {
        ClientConfiguration config = new ClientConfiguration();
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setSocketTimeout(READ_TIMEOUT_MS);
        return new RemoteFiles(AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(S3_DATASHARE_ENDPOINT, S3_REGION))
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
            Files.copy(s3Object.getObjectContent(), get(localFile.getPath()));
        }
    }

    public boolean isSync(final String remoteKey, final File localFile) throws IOException {
        if (localFile.isDirectory()) {
            File localDir = localFile.toPath().resolve(remoteKey).toFile();
            if (! localDir.isDirectory()) {
                return false;
            }
            ObjectListing remoteS3Objects = s3Client.listObjects(bucket, remoteKey);
            Map<String, Long> remoteObjectsMap = remoteS3Objects.getObjectSummaries().stream()
                    .filter(os -> os.getSize() != 0) // because remote dirs are empty keys
                    .collect(toMap(S3ObjectSummary::getKey, S3ObjectSummary::getSize)); // Etag is 128bits MD5 hashed from file

            Map<String, Long> localFilesMap = walk(localDir.toPath(), FileVisitOption.FOLLOW_LINKS)
                    .map(Path::toFile)
                    .filter(File::isFile)
                    .collect(toMap(f -> getKeyFromFile(localFile, f), File::length));
            boolean equals = localFilesMap.equals(remoteObjectsMap);
            if (remoteObjectsMap.isEmpty()) {
                LoggerFactory.getLogger(getClass()).warn("remote object map is empty ({})", remoteKey);
            } else {
                LoggerFactory.getLogger(getClass()).debug("remote {} local {} is equal ? {}", remoteObjectsMap, localFilesMap, equals);
            }
            return equals;
        } else {
            ObjectMetadata objectMetadata = s3Client.getObjectMetadata(bucket, remoteKey);
            return objectMetadata.getContentLength() == localFile.length();
        }
    }

    private String getKeyFromFile(File localFile, File f) {
        return f.getPath().
                replace(localFile.getPath(), "").
                replaceAll("^" + Pattern.quote(File.separator) + "+", "").
                replace(File.separator, "/");
    }

    boolean objectExists(final String key) {
        return s3Client.doesObjectExist(this.bucket, key);
    }

    public void shutdown() { s3Client.shutdown();}
}
