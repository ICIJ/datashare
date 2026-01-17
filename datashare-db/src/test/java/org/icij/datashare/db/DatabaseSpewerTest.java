package org.icij.datashare.db;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.nio.charset.Charset.forName;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;

@RunWith(Parameterized.class)
public class DatabaseSpewerTest {
    @Rule public DbSetupRule dbRule;
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private DatabaseSpewer dbSpewer;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://" + EnvUtils.resolveHost("postgres") + "/dstest?user=dstest&password=test")}
        });
    }

    @AfterClass
    public static void shutdownPools() {
        for (DbSetupRule rule : rulesToClose) {
            rule.shutdown();
        }
    }

    public DatabaseSpewerTest(DbSetupRule rule) {
        dbRule = rule;
        dbSpewer = new DatabaseSpewer(project("prj"), rule.createRepository(), text -> Language.ENGLISH);
        rulesToClose.add(dbRule);
    }

    @Test
    public void test_spew_document_iso8859_encoded_is_stored_in_utf8_and_have_correct_parameters() throws Exception {
        File file = tmp.newFile("test_iso8859-1.txt");
        Files.write(file.toPath(), singletonList("chaîne en iso8859"), forName("ISO-8859-1"));
        TikaDocument tikaDocument = new Extractor().extract(file.toPath());

        dbSpewer.write(tikaDocument);
        Document actual = dbSpewer.repository.getDocument(tikaDocument.getId());
        assertThat(actual.getContent()).isEqualTo("chaîne en iso8859");
        assertThat(actual.getContentEncoding()).isEqualTo(forName("iso8859-1"));
        assertThat(actual.getContentLength()).isEqualTo(18);
        assertThat(actual.getContentType()).isEqualTo("text/plain");
    }
}
