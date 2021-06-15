package org.icij.datashare.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.fest.assertions.Assertions.assertThat;

public class AwsEtagTest {
    final static String seed = "0123456789abcdef";
    final static int BUFFER_SIZE_IN_BYTES = 4 * 1024;
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test
    public void test_etag_1m() throws Exception {
        File file_1m = createFile(1024 * 1024);
        assertThat(AwsEtag.compute(file_1m)).isEqualTo(AwsEtag.parse("14d785daab3823736c981ca82a750c98"));
    }

    @Test
    public void test_etag_10m() throws Exception {
        File file_10m = createFile(1024 * 1024 * 10);
        assertThat(AwsEtag.compute(file_10m)).isEqualTo(AwsEtag.parse("1fae71831e335dd68f69ea6bb50aa161-2"));
    }

    @Test
    public void test_etag_17m() throws Exception {
        File file_17m = createFile(1024 * 1024 * 17);
        assertThat(AwsEtag.compute(file_17m)).isEqualTo(AwsEtag.parse("a4e58d59985e194089a297c64f347063-3"));
    }

    @Test
    public void test_parse() {
        assertThat(AwsEtag.parse("0123456789abcdef0123456789abcdef")).isEqualTo(new AwsEtag("0123456789abcdef0123456789abcdef", 1));
        assertThat(AwsEtag.parse("0123456789abcdef0123456789abcdef-123")).isEqualTo(new AwsEtag("0123456789abcdef0123456789abcdef", 123));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_illegal_argument() {
        AwsEtag.parse("not an md5");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_bad_length() {
        AwsEtag.parse("0123456789abcdef");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_bad_nbparts() {
        AwsEtag.parse("0123456789abcdef0123456789abcdef-ext");
    }

    @Test
    public void test_to_string() {
        assertThat(AwsEtag.parse("0123456789abcdef0123456789abcdef").toString()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(AwsEtag.parse("0123456789abcdef0123456789abcdef-3").toString()).isEqualTo("0123456789abcdef0123456789abcdef-3");
    }

    private File createFile(int sizeInBytes) throws IOException {
        File file = folder.newFile();
        byte[] buffer = createBufferFilledWithSeed(seed.getBytes(StandardCharsets.UTF_8), BUFFER_SIZE_IN_BYTES);
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < sizeInBytes/BUFFER_SIZE_IN_BYTES; i++) {
                bos.write(buffer);
            }
        }
        return file;
    }

    public byte[] createBufferFilledWithSeed(byte[] seed, int sizeInBytes) {
        byte[] buffer = Arrays.copyOf(seed, sizeInBytes);
        for (int last = seed.length; last != 0 && last < sizeInBytes; last <<= 1) {
            System.arraycopy(buffer, 0, buffer, last, Math.min(last << 1, sizeInBytes) - last);
        }
        return buffer;
    }
}
