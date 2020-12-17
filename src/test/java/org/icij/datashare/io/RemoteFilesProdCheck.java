package org.icij.datashare.io;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import static java.nio.file.Files.readAllLines;
import static org.fest.assertions.Assertions.assertThat;

public class RemoteFilesProdCheck {
    RemoteFiles remoteFiles = RemoteFiles.getDefault();

    @Test
    public void test_directory_download() throws Exception {
        Path modelPath = Paths.get(Objects.requireNonNull(ClassLoader.getSystemClassLoader().getResource("testModels")).getPath());

        Path localFile = modelPath.resolve("dist/test/dir1/").resolve("file1.txt");
        assertThat(localFile.toFile().exists()).isFalse();

        remoteFiles.download("dist/test/dir1", modelPath.toFile());

        assertThat(localFile.toFile().exists()).isTrue();
        assertThat(readAllLines(localFile)).containsExactly("content 1");
        assertThat(remoteFiles.isSync("dist/test/dir1/", modelPath.toFile())).isTrue();
        assertThat(Objects.requireNonNull(modelPath.resolve("dist/test").toFile().listFiles()).length).isEqualTo(1); // only dir1
    }

    @After public void tearDown() { remoteFiles.shutdown();}
}
