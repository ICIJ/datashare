package org.icij.datashare.db;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.jooq.SQLDialect;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;

import static java.nio.charset.Charset.forName;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.DbSetupRule.createDatasource;
import static org.icij.datashare.text.Project.project;
import static org.jooq.SQLDialect.POSTGRES_10;
import static org.jooq.SQLDialect.SQLITE;

@RunWith(Parameterized.class)
public class DatabaseSpewerTest {
    @Rule public DbSetupRule dbRule;
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private DatabaseSpewer dbSpewer;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() throws IOException, SQLException {
        return Arrays.asList(new Object[][]{
                {createDatasource(null), SQLITE},
                {createDatasource("jdbc:postgresql://postgresql/test?user=test&password=test"), POSTGRES_10}
        });
    }

    public DatabaseSpewerTest(DataSource dataSource, SQLDialect dialect) throws IOException {
        dbRule = new DbSetupRule(dataSource);
        dbSpewer = new DatabaseSpewer(project("prj"), new JooqRepository(dbRule.dataSource, dialect), new OptimaizeLanguageGuesser());
    }

    @Test
    public void test_spew_document_iso8859_encoded_is_stored_in_utf8_and_have_correct_parameters() throws Exception {
        File file = tmp.newFile("test_iso8859-1.txt");
        Files.write(file.toPath(), singletonList("chaîne en iso8859"), forName("ISO-8859-1"));
        TikaDocument tikaDocument = new DocumentFactory().withIdentifier(new PathIdentifier()).create(file.toPath());
        Reader reader = new Extractor().extract(tikaDocument);

        dbSpewer.write(tikaDocument, reader);

        Document actual = dbSpewer.repository.getDocument(tikaDocument.getId());
        assertThat(actual.getContent()).isEqualTo("chaîne en iso8859");
        assertThat(actual.getContentEncoding()).isEqualTo(forName("iso8859-1"));
        assertThat(actual.getContentLength()).isEqualTo(18);
        assertThat(actual.getContentType()).isEqualTo("text/plain");
    }
}
