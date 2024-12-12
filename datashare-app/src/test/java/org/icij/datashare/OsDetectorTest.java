package org.icij.datashare;

import static org.fest.assertions.Assertions.assertThat;

import org.junit.Test;

public class OsDetectorTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_os_unknown() {
        OsArchDetector.OS.fromSystemString("freebsd");
    }

    @Test
    public void test_os_enum_this_computer() {
        assertThat(OsArchDetector.OS.fromSystem()).isEqualTo(OsArchDetector.OS.fromSystemString(System.getProperty("os.name")));
    }

    @Test
    public void test_os_enum_mac() {
        assertThat(OsArchDetector.OS.fromSystemString("macosx")).isEqualTo(OsArchDetector.OS.macos);
        assertThat(OsArchDetector.OS.fromSystemString("macos")).isEqualTo(OsArchDetector.OS.macos);
        assertThat(OsArchDetector.OS.fromSystemString("mac")).isEqualTo(OsArchDetector.OS.macos);
    }

    @Test
    public void test_os_enum_windows() {
        assertThat(OsArchDetector.OS.fromSystemString("Windows 11")).isEqualTo(OsArchDetector.OS.windows);
    }

    @Test
    public void test_os_enum_linux() {
        assertThat(OsArchDetector.OS.fromSystemString("Linux")).isEqualTo(OsArchDetector.OS.linux);
        assertThat(OsArchDetector.OS.fromSystemString("nux")).isEqualTo(OsArchDetector.OS.linux);
        assertThat(OsArchDetector.OS.fromSystemString("nix")).isEqualTo(OsArchDetector.OS.linux);
        assertThat(OsArchDetector.OS.fromSystemString("aix")).isEqualTo(OsArchDetector.OS.linux);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_arch_unknown() {
        OsArchDetector.OS.fromSystemString("x86_32");
    }

    @Test
    public void test_arch_this_computer() {
        assertThat(OsArchDetector.ARCH.fromSystem()).isEqualTo(OsArchDetector.ARCH.fromSystemString(System.getProperty("os.arch")));
    }

    @Test
    public void test_arch_arm() {
        assertThat(OsArchDetector.ARCH.fromSystemString("aarch64")).isEqualTo(OsArchDetector.ARCH.aarch64);
        assertThat(OsArchDetector.ARCH.fromSystemString("arm64")).isEqualTo(OsArchDetector.ARCH.aarch64);
    }

    @Test
    public void test_arch_64() {
        assertThat(OsArchDetector.ARCH.fromSystemString("amd64")).isEqualTo(OsArchDetector.ARCH.x86_64);
        assertThat(OsArchDetector.ARCH.fromSystemString("x86_64")).isEqualTo(OsArchDetector.ARCH.x86_64);
    }
}
