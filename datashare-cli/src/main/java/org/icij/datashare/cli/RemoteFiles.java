package org.icij.datashare.cli;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

import java.io.*;
import java.nio.file.Paths;

import static java.nio.file.Files.newBufferedWriter;

public class RemoteFiles {
    private final AmazonS3 s3Client;
    private final String bucket;

    public RemoteFiles(final AmazonS3 s3Client, final String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public void upload(final String key, final InputStream inputStream) {
        s3Client.putObject(new PutObjectRequest(this.bucket, key, inputStream, new ObjectMetadata()));
    }

    public boolean objectExists(final String key) {
        return s3Client.doesObjectExist(this.bucket, key);
    }

    public void download(String fileKey, File targetFolder) throws IOException {
        final S3Object s3Object = s3Client.getObject(this.bucket, fileKey);

        BufferedReader reader = new BufferedReader(new InputStreamReader(s3Object.getObjectContent()));
        BufferedWriter writer = newBufferedWriter(Paths.get(targetFolder.getAbsolutePath() + '/' + fileKey));

        char[] buffer = new char[4096];
        while (reader.read(buffer) != -1) {
            writer.write(buffer);
        }
        writer.close();
        reader.close();
    }
}
