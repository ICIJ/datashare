package org.icij.datashare.text.hashing;

import org.icij.datashare.text.Hasher;
import org.junit.Test;

import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Hasher.SHA_256;
import static org.icij.datashare.text.Hasher.SHA_384;

public class HasherTest {
    private String text = "Hashing Test";

    @Test
    public void test_hash_md5() {
        assertThat(Hasher.MD5.hash(text)).isEqualTo("dfb174995e25625be6b5a885a0880198");
    }

    @Test
    public void test_hash_sha1() {
        assertThat(Hasher.SHA_1.hash(text)).isEqualTo("5cdf2e1c7e5be2f4f3a004ba7bc9caaa4bac79be");
    }

    @Test
    public void test_hash_sha256() {
        assertThat(SHA_256.hash(text)).
                isEqualTo("6c93551b9465d43b2363e5d6b6a6ebf4467ff0032784223acf4d359cd809dd85");
    }

    @Test
    public void test_hash_sha884() {
        assertThat(SHA_384.hash(text)).
                isEqualTo("6f04e5a3ab380e1efde57876eb0614ee9cd108f2e6012f9ceb1873486337199f0da" +
                        "5389c1c5a08bcee3adf3dcdfc71f5");
    }

    @Test
    public void test_hash_sha512() {
        assertThat(Hasher.SHA_512.hash(text)).
                isEqualTo("8c37b5a8bf4a3ede1e0d4567b2ef0c933bf246923d89226c01b264336ac333333b7f2" +
                        "43e06ee3299b770aa7ff72aa9ccc470725ca3d12c1f0aa1fd85c2eb83fc");
    }

    @Test
    public void test_hash_file() {
        assertThat(Hasher.SHA_1.hash(Paths.get(getClass().getResource("/sampleFile.txt").getPath()))).
                isEqualTo(Hasher.SHA_1.hash("test"));
    }

    @Test
    public void test_hash_file_with_prefix() {
        assertThat(Hasher.SHA_1.hash(Paths.get(getClass().getResource("/sampleFile.txt").getPath()), "prefix")).
                isEqualTo(Hasher.SHA_1.hash("prefixtest"));
    }

    @Test
    public void test_get_hasher_from_length() {
        assertThat(Hasher.valueOf(64)).isEqualTo(SHA_256);
        assertThat(Hasher.valueOf(96)).isEqualTo(SHA_384);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_get_hasher_with_illegal_length() {
        Hasher.valueOf(123);
    }

    @Test
    public void test_shorten() {
        assertThat(Hasher.shorten("my_very_long_hash_string", 2)).isEqualTo("my...ng");
    }
}
