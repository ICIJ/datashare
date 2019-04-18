package org.icij.datashare.db;


import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.DbSetupRule.createSqlite;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.icij.datashare.text.nlp.Pipeline.Type.OPENNLP;

public class JooqRepositoryTest {
    @Rule public DbSetupRule dbRule = new DbSetupRule(createSqlite());
    private JooqRepository repository = new JooqRepository(new DataSourceConnectionProvider(dbRule.dataSource), SQLDialect.SQLITE);

    @Test
    public void test_create_document() throws Exception {
        Document document = new Document(Paths.get("/path/to/doc"), "content", FRENCH,
                Charset.defaultCharset(), "test/plain", new HashMap<String, String>() {{
                    put("key 1", "value 1");
                    put("key 2", "value 2");
        }}, Document.Status.INDEXED, Pipeline.set(CORENLP, OPENNLP), 432L);

        repository.create(document);

        Document actual = repository.getDocument(document.getId());
        assertThat(actual).isEqualTo(document);
        assertThat(actual.getMetadata()).isEqualTo(document.getMetadata());
        assertThat(actual.getNerTags()).isEqualTo(document.getNerTags());
        assertThat(actual.getExtractionDate()).isEqualTo(document.getExtractionDate());
    }

    @Test
    public void test_create_named_entity_list() throws SQLException {
        List<NamedEntity> namedEntities = Arrays.asList(
                NamedEntity.create(PERSON, "mention 1", 123, "doc_id", CORENLP, GERMAN),
                NamedEntity.create(PERSON, "mention 2", 321, "doc_id", CORENLP, ENGLISH));

        repository.create(namedEntities);

        NamedEntity actualNe1 = repository.getNamedEntity(namedEntities.get(0).getId());
        assertThat(actualNe1).isEqualTo(namedEntities.get(0));
        assertThat(actualNe1.getCategory()).isEqualTo(PERSON);
        assertThat(actualNe1.getExtractor()).isEqualTo(CORENLP);
        assertThat(actualNe1.getExtractorLanguage()).isEqualTo(GERMAN);

        assertThat(repository.getNamedEntity(namedEntities.get(1).getId())).isEqualTo(namedEntities.get(1));
    }
}
