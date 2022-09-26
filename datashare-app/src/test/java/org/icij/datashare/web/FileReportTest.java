package org.icij.datashare.web;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

import static org.fest.assertions.Assertions.assertThat;


public class FileReportTest {
    @Test
    public void test_report_for_file() throws IOException {
        assertThat(new FileReport(getFile("email.eml")).getProt()).isEqualTo("-rw-r--r--");
    }

    @Test
    public void test_report_for_directory() throws IOException {
        assertThat(new FileReport(getFile("docs")).getProt()).isEqualTo("drwxr-xr-x");
    }

    @Test
    public void test_get_type_for_directory() throws IOException {
        assertThat(new FileReport(getFile("app")).getType()).isEqualTo(FileReport.Type.DIRECTORY);
    }

    @Test
    public void test_get_type_for_file() throws IOException {
        assertThat(new FileReport(getFile("extensions.json")).getType()).isEqualTo(FileReport.Type.FILE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_add_children_on_file() throws IOException {
        new FileReport(getFile("email.eml")).add(new FileReport(getFile("email.eml")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_add_children_on_directory_that_is_not_parent() throws IOException {
        new FileReport(getFile("docs")).add(new FileReport(getFile("email.eml")));
    }

    @Test
    public void test_add_children_on_directory() throws IOException {
        FileReport fr = new FileReport(getFile("docs"));
        fr.add(new FileReport(getFile("docs/doc.txt")));
        assertThat(fr.getChildren()).hasSize(1);
    }

    @Test
    public void test_file_report_visitor_with_depth_one() throws IOException {
        File docs = getFile("docs");
        FileReport rootReport = new FileReport(docs);
        Files.walkFileTree(docs.toPath(), new FileReport.FileReportVisitor(rootReport));
        assertThat(rootReport.getChildren()).hasSize(3);
        assertThat(rootReport.getChildren().get(0).file.getName()).isEqualTo("foo");
        assertThat(rootReport.getChildren().get(0).getChildren()).hasSize(0);
    }

    @Test
    public void test_file_report_visitor_with_depth() throws IOException {
        File docs = getFile("docs");
        FileReport rootReport = new FileReport(docs);
        Files.walkFileTree(docs.toPath(), new FileReport.FileReportVisitor(rootReport, 2));
        assertThat(rootReport.getChildren()).hasSize(3);
        assertThat(rootReport.getChildren().get(0).file.getName()).isEqualTo("foo");
        assertThat(rootReport.getChildren().get(1).file.getName()).isEqualTo("embedded_doc.eml");
        assertThat(rootReport.getChildren().get(1).fileProt()).isEqualTo("rw-r--r--");

        assertThat(rootReport.getChildren().get(0).getChildren()).hasSize(1);
        assertThat(rootReport.getChildren().get(0).getChildren().get(0).file.getName()).isEqualTo("bar.txt");
    }

    @Test
    public void test_get_name() throws IOException {
        assertThat(new FileReport(getFile("extensions.json")).getName()).endsWith("datashare/datashare-app/target/test-classes/extensions.json");
        assertThat(new FileReport(getFile("data")).getName()).endsWith("datashare/datashare-app/target/test-classes/data");
    }

    @NotNull
    private File getFile(String fileName) {
        return new File(Objects.requireNonNull(getClass().getClassLoader().getResource(fileName)).getFile());
    }
}