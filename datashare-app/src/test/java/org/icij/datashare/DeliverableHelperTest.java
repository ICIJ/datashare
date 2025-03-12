package org.icij.datashare;

import org.icij.datashare.OsArchDetector.ARCH;
import org.icij.datashare.OsArchDetector.OS;
import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;

import static org.fest.assertions.Assertions.assertThat;

public class DeliverableHelperTest {
    @Test
    public void test_url() throws MalformedURLException {
        OsArchDetector detector = new OsArchDetector(OS.linux, ARCH.x86_64);
        assertThat(DeliverableHelper.hostSpecificUrl(detector, new URL("http://foo/bar/tag/1.2.3/baz-1.2.3"), "1.2.3").toString())
                .isEqualTo("http://foo/bar/tag/1.2.3/baz-linux-x86_64-1.2.3");
    }
}