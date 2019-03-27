package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.HashMap;

public class Neo4jNamedEntityRepository implements NamedEntityRepository {
    private final JdbcTemplate jdbcTemplate;

    public Neo4jNamedEntityRepository() throws SQLException {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:neo4j:bolt://neo4j/?user=neo4j&password=dev"));
        jdbcTemplate.update("CREATE CONSTRAINT ON (doc:Document) ASSERT doc.id IS UNIQUE");
        jdbcTemplate.update("CREATE CONSTRAINT ON (ne:NamedEntity) ASSERT ne.id IS UNIQUE");
    }

    @Override
    public NamedEntity get(String id) {
        return (NamedEntity) jdbcTemplate.queryForObject(
                "MATCH (ne:NamedEntity)\n" +
                    "WHERE ne.id =~ {1}\n" +
                    "RETURN ne",
                new Object[] {id},
                (rs, i) -> NamedEntity.create(NamedEntity.Category.parse(rs.getString("category")),
                rs.getString("mention"),
                rs.getInt("offset"),
                rs.getString("docId"),
                Pipeline.Type.valueOf(rs.getString("pipeline")),
                Language.parse(rs.getString("language"))));
    }

    @Override
    public int create(Document document) {
        return jdbcTemplate.update("CREATE (doc:Document {id: ?, path: ?}) RETURN doc", new Object[] {
                document.getId(), document.getPath().toString()
        });
    }

    @Override
    public int create(NamedEntity ne) {
        return jdbcTemplate.update(
                "MATCH (doc:Document {id: ?})\n" +
                "CREATE (ne:NamedEntity {id: ?, mention: ?}))-[rel:IS_MENTIONNED {offset: ?}]->(doc)",
                new Object[] {
                        ne.getDocumentId(), ne.getId(), ne.getMention(), ne.getOffset()
                });
    }

    @Override
    public void update(NamedEntity ne) {
        throw new NotImplementedException();
    }

    @Override
    public NamedEntity delete(String id) {
        return null;
    }

    public static void main(String[] args) throws SQLException {
        Neo4jNamedEntityRepository neo4jRepository = new Neo4jNamedEntityRepository();
        Document document = new Document(Paths.get("/foo/bar.txt"),
                "This is a content with Gael Giraud",
                Language.FRENCH,
                Charset.defaultCharset(),
                "text/plain",
                new HashMap<String, String>() {{
                    put("key1", "value1");
                    put("key2", "value2");
                }},
                Document.Status.INDEXED);
        assert neo4jRepository.create(document) > 0;

        NamedEntity ne = NamedEntity.create(NamedEntity.Category.PERSON, "Gael Giraud", 23, document.getId(), Pipeline.Type.CORENLP, Language.FRENCH);
        assert neo4jRepository.create(ne) > 0;

        neo4jRepository.get(ne.getId());
    }
}
