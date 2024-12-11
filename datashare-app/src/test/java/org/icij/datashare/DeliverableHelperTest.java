package org.icij.datashare;

import org.icij.datashare.DeliverableHelper.ARCH;
import org.icij.datashare.DeliverableHelper.OS;
import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;

import static org.fest.assertions.Assertions.assertThat;

public class DeliverableHelperTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_os_unknown() {
        OS.fromSystemString("freebsd");
    }

    @Test
    public void test_os_enum_this_computer() {
        assertThat(OS.fromSystem()).isEqualTo(OS.fromSystemString(System.getProperty("os.name")));
    }

    @Test
    public void test_os_enum_mac() {
        assertThat(OS.fromSystemString("macosx")).isEqualTo(OS.macos);
        assertThat(OS.fromSystemString("macos")).isEqualTo(OS.macos);
        assertThat(OS.fromSystemString("mac")).isEqualTo(OS.macos);
    }

    @Test
    public void test_os_enum_windows() {
        assertThat(OS.fromSystemString("Windows 11")).isEqualTo(OS.windows);
    }

    @Test
    public void test_os_enum_linux() {
        assertThat(OS.fromSystemString("Linux")).isEqualTo(OS.linux);
        assertThat(OS.fromSystemString("nux")).isEqualTo(OS.linux);
        assertThat(OS.fromSystemString("nix")).isEqualTo(OS.linux);
        assertThat(OS.fromSystemString("aix")).isEqualTo(OS.linux);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_arch_unknown() {
        OS.fromSystemString("x86_32");
    }

    @Test
    public void test_arch_this_computer() {
        assertThat(ARCH.fromSystem()).isEqualTo(ARCH.fromSystemString(System.getProperty("os.arch")));
    }

    @Test
    public void test_arch_arm() {
        assertThat(ARCH.fromSystemString("aarch64")).isEqualTo(ARCH.aarch64);
        assertThat(ARCH.fromSystemString("arm64")).isEqualTo(ARCH.aarch64);
    }

    @Test
    public void test_arch_64() {
        assertThat(ARCH.fromSystemString("amd64")).isEqualTo(ARCH.x86_64);
        assertThat(ARCH.fromSystemString("x86_64")).isEqualTo(ARCH.x86_64);
    }

    @Test
    public void test_url() throws MalformedURLException {
        assertThat(DeliverableHelper.hostSpecificUrl(new URL("http://foo/bar/baz-1.2.3"), "1.2.3").toString())
                .isEqualTo("http://foo/bar/baz-linux-x86_64-1.2.3");
    }
}