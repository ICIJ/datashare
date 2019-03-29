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
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:neo4j:bolt://neo4j/?user=neo4j&password=dev&flatten=-1"));
        jdbcTemplate.update("CREATE CONSTRAINT ON (doc:Document) ASSERT doc.id IS UNIQUE");
        jdbcTemplate.update("CREATE CONSTRAINT ON (ne:NamedEntity) ASSERT ne.mention IS UNIQUE");
    }

    @Override
    public NamedEntity get(String id) {
        return (NamedEntity) jdbcTemplate.queryForObject(
                "MERGE (ne)-[rel:IS_MENTIONED {id: ?}]->(doc)\n" +
                        "RETURN rel, ne, doc",
                new Object[]{id},
                (rs, i) ->
                        NamedEntity.create(NamedEntity.Category.parse(rs.getString("rel.category")),
                                rs.getString("ne.mention"),
                                rs.getInt("rel.offset"),
                                rs.getString("doc.id"),
                                Pipeline.Type.valueOf(rs.getString("rel.pipeline")),
                                Language.parse(rs.getString("rel.language"))));
    }

    @Override
    public int create(Document document) {
        return jdbcTemplate.update(
                "MERGE (doc:Document {id: ?}) " +
                        "SET doc.path = ?" +
                        "RETURN doc", new Object[]{
                        document.getId(), document.getPath().toString()
                });
    }

    @Override
    public int create(NamedEntity ne) {
        return jdbcTemplate.update(
                "MATCH (doc:Document {id: ?})\n" +
                        "MERGE (ne:NamedEntity {mention: ?})\n" +
                        "MERGE (ne)-[rel:IS_MENTIONED {id: ?}]->(doc)\n" +
                        "SET rel.category = ?,\n" +
                        "    rel.pipeline = ?,\n" +
                        "    rel.language = ?,\n" +
                        "    rel.offset = ?\n" +
                        "RETURN rel",
                new Object[]{
                        ne.getDocumentId(),
                        ne.getMention(),
                        ne.getId(),
                        ne.getCategory().toString(),
                        ne.getExtractor().toString(),
                        ne.getExtractorLanguage().toString(),
                        ne.getOffset()
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
        neo4jRepository.create(document);

        NamedEntity ne = NamedEntity.create(NamedEntity.Category.PERSON, "Gael Giraud", 23, document.getId(), Pipeline.Type.CORENLP, Language.FRENCH);
        neo4jRepository.create(ne);

        NamedEntity namedEntity = neo4jRepository.get(ne.getId());
        System.out.println(namedEntity);
    }
}
