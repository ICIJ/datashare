package org.icij.datashare.io;

import org.icij.datashare.text.Hasher;

import java.io.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.icij.datashare.text.Hasher.getHex;

/**
 * inspired by
 * https://stackoverflow.com/questions/6256434/the-md5-from-a-local-file-and-the-md5-etag-from-s3-is-not-the-same
 * and
 * https://stackoverflow.com/questions/12186993/what-is-the-algorithm-to-compute-the-amazon-s3-etag-for-a-file-larger-than-5gb
 */
public class AwsEtag {
    private static final int BOTO3_CHUNK_SIZE = 8388608;
    private static final Pattern MD5_PATTERN = Pattern.compile( "[a-f0-9]{32}" ) ;
    private static final Pattern FULL_ETAG_PATTERN = Pattern.compile( "(" + MD5_PATTERN.pattern() + ")(?:-([0-9]+))?");
    private final String md5;
    private final int nbParts;

    AwsEtag(String md5, int nbParts) {
        this.md5 = md5;
        this.nbParts = nbParts;
    }

    public static AwsEtag parse(String etagString) {
        final Matcher matcher = FULL_ETAG_PATTERN.matcher( etagString );
        if (matcher.matches()) {
            final String md5 = matcher.group(1);
            final String nbParts = matcher.group(2);
            return new AwsEtag(md5, nbParts == null ? 1 : Integer.parseInt(nbParts));
        }
        throw new IllegalArgumentException("Invalid format for Etag : <" + etagString + ">");
    }

    public static AwsEtag compute(File file) {
        return compute(file, BOTO3_CHUNK_SIZE);
    }

    public static AwsEtag compute(File file, int chunkSize) {
        int nbChunks = (int) Math.ceil(file.length() / (double) chunkSize);
        byte[] buffer = new byte[chunkSize];
        try {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(file));
            ByteArrayOutputStream resultOutputStream = new ByteArrayOutputStream();
            for (int chunkIndex = 0; chunkIndex < nbChunks; chunkIndex++) {
                int nbBytesRead = bufferedInputStream.read(buffer);
                if (nbBytesRead == buffer.length) {
                    resultOutputStream.write(Hasher.MD5.hash(buffer));
                } else {
                    resultOutputStream.write(Hasher.MD5.hash(Arrays.copyOf(buffer, nbBytesRead)));
                }
            }
            return nbChunks == 1 ? new AwsEtag(getHex(resultOutputStream.toByteArray()), 1):
                    new AwsEtag(getHex(Hasher.MD5.hash(resultOutputStream.toByteArray())), nbChunks);
        } catch (IOException ioex) {
            throw new RuntimeException(ioex);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AwsEtag awsEtag = (AwsEtag) o;
        return nbParts == awsEtag.nbParts && Objects.equals(md5, awsEtag.md5);
    }

    @Override
    public int hashCode() {
        return Objects.hash(md5, nbParts);
    }

    @Override
    public String toString() {
        return nbParts == 1 ? md5: md5 + '-' + nbParts;
    }
}
