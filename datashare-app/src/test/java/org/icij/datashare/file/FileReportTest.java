package org.icij.datashare.file;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.file.AbstractFileReport.Type.DIRECTORY;


public class FileReportTest {
    @Test
    public void test_report_for_file() throws IOException {
        assertThat(new FileReport(getFile("email.eml")).getProt()).isIn("-rw-r--r--", "-rw-rw-r--"); // TBF: group can write in devcontainer
    }

    @Test
    public void test_report_for_directory() throws IOException {
        assertThat(new DirectoryReport(getFile("docs")).getProt()).isEqualTo("drwxr-xr-x");
    }

    @Test
    public void test_get_type_for_directory() throws IOException {
        assertThat(new DirectoryReport(getFile("app")).getType()).isEqualTo(DIRECTORY);
    }

    @Test
    public void test_get_type_for_file() throws IOException {
        assertThat(new FileReport(getFile("extensions.json")).getType()).isEqualTo(DirectoryReport.Type.FILE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_add_children_on_directory_that_is_not_parent() throws IOException {
        new DirectoryReport(getFile("docs")).add(new DirectoryReport(getFile("email.eml")));
    }

    @Test
    public void test_add_children_on_directory() throws IOException {
        DirectoryReport fr = new DirectoryReport(getFile("docs"));
        fr.add(new FileReport(getFile("docs/doc.txt")));
        assertThat(fr.getContents()).hasSize(1);
    }

    @Test
    public void test_file_report_visitor_with_depth_one() throws IOException {
        File docs = getFile("docs");
        DirectoryReport rootReport = new DirectoryReport(docs);
        Files.walkFileTree(docs.toPath(), new FileReportVisitor(rootReport));
        List<AbstractFileReport> children = rootReport.getContents();
        assertThat(children).hasSize(3);
        assertThat(children.get(2).file.getName()).isEqualTo("foo");
        assertThat(children.get(2).getType()).isEqualTo(DIRECTORY);
        assertThat(((DirectoryReport)children.get(2)).getContents()).hasSize(0);
    }

    @Test
    public void test_file_report_visitor_with_depth() throws IOException {
        File docs = getFile("docs");
        DirectoryReport rootReport = new DirectoryReport(docs);
        Files.walkFileTree(docs.toPath(), new FileReportVisitor(rootReport, 2));
        assertThat(rootReport.getContents()).hasSize(3);
        assertThat(rootReport.getContents().get(1).file.getName()).isEqualTo("embedded_doc.eml");
        assertThat(rootReport.getContents().get(1).fileProt()).isIn("rw-r--r--", "rw-rw-r--"); // TBF: group can write in devcontainer

        assertThat(rootReport.getContents().get(2).file.getName()).isEqualTo("foo");
        assertThat(((DirectoryReport)rootReport.getContents().get(2)).getContents()).hasSize(1);
        assertThat(((DirectoryReport)rootReport.getContents().get(2)).getContents().get(0).file.getName()).isEqualTo("bar.txt");
    }

    @Test
    public void test_get_name() throws IOException {
        assertThat(new DirectoryReport(getFile("extensions.json")).getName()).endsWith("datashare-app/target/test-classes/extensions.json");
        assertThat(new DirectoryReport(getFile("data")).getName()).endsWith("datashare-app/target/test-classes/data");
    }

    @NotNull
    private File getFile(String fileName) {
        return new File(Objects.requireNonNull(getClass().getClassLoader().getResource(fileName)).getFile());
    }
}
