package org.icij.datashare.io;

import static java.nio.file.Files.walk;
import static java.util.stream.Collectors.toMap;
import static software.amazon.awssdk.http.Header.CONTENT_LENGTH;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.DirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.DownloadDirectoryRequest;
import software.amazon.awssdk.transfer.s3.model.UploadDirectoryRequest;

public class RemoteFiles {
    private static final String S3_DATASHARE_BUCKET_NAME = "datashare-nlp";
    private static final String S3_DATASHARE_ENDPOINT = "https://s3-accelerate.amazonaws.com/";
    private static final String S3_REGION = "us-east-1";
    private static final int READ_TIMEOUT_MS = 120 * 1000;
    private static final int CONNECTION_TIMEOUT_MS = 30 * 1000;
    private final S3AsyncClient s3Client;
    private final String bucket;

    RemoteFiles(final S3AsyncClient s3Client, final String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public static RemoteFiles getDefault() {
        return getWith(S3_DATASHARE_BUCKET_NAME, S3_DATASHARE_ENDPOINT, false);
    }

    public static RemoteFiles getWith(String bucketName, String endPoint, boolean pathStyleAccessEnabled) {
        NettyNioAsyncHttpClient.Builder httpClientBuilder = NettyNioAsyncHttpClient.builder()
            .connectionTimeout(Duration.ofMillis(CONNECTION_TIMEOUT_MS))
            .readTimeout(Duration.ofMillis(READ_TIMEOUT_MS));

        S3AsyncClientBuilder s3ClientBuilder = S3AsyncClient.builder()
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .httpClientBuilder(httpClientBuilder)
            .region(Region.of(S3_REGION));

        if (endPoint != null && !endPoint.isEmpty()) {
            s3ClientBuilder.endpointOverride(URI.create(endPoint));
        }

        if (pathStyleAccessEnabled) {
            s3ClientBuilder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build());
        }

        return new RemoteFiles(s3ClientBuilder.build(), bucketName);
    }

    public void upload(final File localFile, final String remoteKey)
        throws InterruptedException, FileNotFoundException {
        if (localFile.isDirectory()) {
            try (S3TransferManager transferManager = S3TransferManager.builder().s3Client(s3Client).build()) {
                UploadDirectoryRequest request = UploadDirectoryRequest.builder()
                    .bucket(bucket)
                    .s3Prefix(remoteKey)
                    .source(localFile.toPath())
                    .maxDepth(null)
                    .build();
                final DirectoryUpload uploads = transferManager.uploadDirectory(request);
                uploads.completionFuture().join();
            }
        } else {
            final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(remoteKey)
                .metadata(Map.of(CONTENT_LENGTH, String.valueOf(localFile.length())))
                .build();
            s3Client.putObject(putObjectRequest, localFile.toPath()).join();
        }
    }

    public void download(final String remoteKey, final File localFile) throws InterruptedException, IOException {
        if (localFile.isDirectory()) {
            try (S3TransferManager transferManager = S3TransferManager.builder().s3Client(s3Client).build()) {
                DownloadDirectoryRequest request = DownloadDirectoryRequest.builder()
                    .bucket(bucket)
                    .destination(localFile.toPath())
                    .filter(o -> o.key().startsWith(remoteKey))
                    .build();
                transferManager.downloadDirectory(request).completionFuture().join();
            }
        } else {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(remoteKey).build();
            if (!localFile.getParentFile().exists()) {
                localFile.getParentFile().mkdirs();
            }
            s3Client.getObject(getObjectRequest, localFile.toPath()).join();
        }
    }

    public boolean isSync(final String remoteKey, final File localFile) throws IOException {
        if (localFile.isDirectory()) {
            File localDir = localFile.toPath().resolve(remoteKey).toFile();
            if (!localDir.isDirectory()) {
                return false;
            }
            ListObjectsRequest listObjectsRequest = ListObjectsRequest.builder()
                .bucket(bucket).prefix(remoteKey).build();
            ListObjectsResponse response = s3Client.listObjects(listObjectsRequest).join();
            Map<String, Long> remoteObjectsMap = response.contents().stream()
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
                LoggerFactory.getLogger(getClass())
                    .debug("remote {} local {} is equal ? {}", remoteObjectsMap, localFilesMap, equals);
            }
            return equals;
        } else {
            HeadObjectRequest getObjectAttributesRequest = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(remoteKey)
                .build();
            HeadObjectResponse response = s3Client.headObject(getObjectAttributesRequest).join();
            return response.contentLength() == localFile.length();
        }
    }

    void createBucket() {
        s3Client.listBuckets().join().buckets().stream()
            .filter(b -> bucket.equals(b.name()))
            .findAny()
            .ifPresentOrElse(b -> {
            }, () -> s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join());
    }

    void deleteBucket() {
        CompletableFuture<?>[] futures = s3Client
                .listObjects(ListObjectsRequest.builder().bucket(bucket).build()).join().contents()
                .stream()
                .map(o -> s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(o.key()).build()))
                .toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(futures).join();
        s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build()).join();
    }

    private String getKeyFromFile(File localFile, File f) {
        return f.getPath().
            replace(localFile.getPath(), "").
            replaceAll("^" + Pattern.quote(File.separator) + "+", "").
            replace(File.separator, "/");
    }

    boolean objectExists(final String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof NoSuchKeyException) {
                return false;
            }
            throw e;
        }
        return true;
    }

    public void shutdown() {
        s3Client.close();
    }
}
