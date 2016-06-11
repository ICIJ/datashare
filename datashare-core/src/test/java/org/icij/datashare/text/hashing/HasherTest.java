package org.icij.datashare.text.hashing;

import org.icij.datashare.test.TestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Created by julien on 5/3/16.
 */
public class HasherTest extends TestBase {

    @Test
    public void testHashString() throws Throwable {
        String text = "Hashing Test";

        Assert.assertEquals(
                "dfb174995e25625be6b5a885a0880198",
                Hasher.MD5.hash(text)
        );

        Assert.assertEquals(
                "5cdf2e1c7e5be2f4f3a004ba7bc9caaa4bac79be",
                Hasher.SHA_1.hash(text)
        );

        Assert.assertEquals(
                "6c93551b9465d43b2363e5d6b6a6ebf4467ff0032784223acf4d359cd809dd85",
                Hasher.SHA_256.hash(text)
        );

        Assert.assertEquals(
                "6f04e5a3ab380e1efde57876eb0614ee9cd108f2e6012f9ceb1873486337199f0da5389c1c5a08bcee3adf3dcdfc71f5",
                Hasher.SHA_384.hash(text)
        );

        Assert.assertEquals(
                "8c37b5a8bf4a3ede1e0d4567b2ef0c933bf246923d89226c01b264336ac333333b7f243e06ee3299b770aa7ff72aa9ccc470725ca3d12c1f0aa1fd85c2eb83fc",
                Hasher.SHA_512.hash(text)
        );
    }

}
