package org.icij.datashare.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.DirectoryDownload;
import software.amazon.awssdk.transfer.s3.model.DirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.DownloadDirectoryRequest;
import software.amazon.awssdk.transfer.s3.model.UploadDirectoryRequest;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import static java.nio.file.Files.walk;
import static java.util.stream.Collectors.toMap;

public class RemoteFiles {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static final String S3_DATASHARE_BUCKET_NAME = "datashare-nlp";
    private static final String S3_DATASHARE_ENDPOINT = "https://s3-accelerate.amazonaws.com/";
    private static final Region S3_REGION = Region.US_EAST_1;
    private static final int CONNECTION_TIMEOUT_MS = 30 * 1000;
    private final S3AsyncClient s3Client;
    private final String bucket;

    RemoteFiles(final S3AsyncClient s3Client, final String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public static RemoteFiles getDefault() {
        return getWithEndPoint(S3_DATASHARE_ENDPOINT);
    }

    public static RemoteFiles getWithEndPoint(String endPoint) {
        S3AsyncClient client = S3AsyncClient.crtBuilder()
                .region(S3_REGION)
                .endpointOverride(URI.create(endPoint))
                .httpConfiguration(c -> c.connectionTimeout(Duration.ofMillis(CONNECTION_TIMEOUT_MS)))
                .forcePathStyle(true)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .build();

        return new RemoteFiles(client, S3_DATASHARE_BUCKET_NAME);
    }

    public void upload(final File localFile, final String remoteKey) throws InterruptedException, FileNotFoundException {
        if (localFile.isDirectory()) {
            try (S3TransferManager transferManager = S3TransferManager.builder()
                    .s3Client(s3Client)
                    .build()) {
                DirectoryUpload directoryUpload = transferManager.uploadDirectory(UploadDirectoryRequest.builder()
                        .source(localFile.toPath())
                        .bucket(this.bucket)
                        .build());

                CompletedDirectoryUpload completedDirectoryUpload = directoryUpload.completionFuture().join();
                completedDirectoryUpload.failedTransfers()
                        .forEach(fail -> logger.warn("Object [{}] failed to transfer", fail.toString()));
            }
        } else {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(remoteKey)
                    .build();
            s3Client.putObject(objectRequest, AsyncRequestBody.fromFile(localFile));
        }
    }

    public void download(final String remoteKey, final File localFile) throws InterruptedException, IOException {
        if (localFile.isDirectory()) {
            try (S3TransferManager transferManager = S3TransferManager.builder()
                    .s3Client(s3Client)
                    .build()) {
                DirectoryDownload directoryDownload = transferManager.downloadDirectory(DownloadDirectoryRequest.builder()
                        .destination(localFile.toPath())
                        .bucket(this.bucket)
                        .build());
                CompletedDirectoryDownload completedDirectoryDownload = directoryDownload.completionFuture().join();

                completedDirectoryDownload.failedTransfers()
                        .forEach(fail -> logger.warn("Object [{}] failed to download", fail.toString()));
            }
        } else {
            s3Client.getObject(GetObjectRequest.builder().bucket(this.bucket).key(remoteKey).build(), localFile.toPath());
        }
    }

    public boolean isSync(final String remoteKey, final File localFile) throws IOException, ExecutionException, InterruptedException {
        if (localFile.isDirectory()) {
            File localDir = localFile.toPath().resolve(remoteKey).toFile();
            if (! localDir.isDirectory()) {
                return false;
            }
            List<S3Object> remoteS3Objects = s3Client.listObjects(ListObjectsRequest.builder().bucket(this.bucket).prefix(remoteKey).build()).get().contents();
            Map<String, Long> remoteObjectsMap = remoteS3Objects.stream()
                    .filter(os -> os.size() != 0) // because remote dirs are empty keys
                    .collect(toMap(S3Object::key, S3Object::size)); // Etag is 128bits MD5 hashed from file

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
            GetObjectAttributesResponse objectAttributes = s3Client.getObjectAttributes(GetObjectAttributesRequest.builder().bucket(this.bucket).key(remoteKey).build()).get();
            return objectAttributes.objectSize() == localFile.length();
        }
    }

    private String getKeyFromFile(File localFile, File f) {
        return f.getPath().
                replace(localFile.getPath(), "").
                replaceAll("^" + Pattern.quote(File.separator) + "+", "").
                replace(File.separator, "/");
    }

    boolean objectExists(final String key) throws ExecutionException, InterruptedException {
        return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).get().hasMetadata();
    }

    public void shutdown() { s3Client.close();}
}
