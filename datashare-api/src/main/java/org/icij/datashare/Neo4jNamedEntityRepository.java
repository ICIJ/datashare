package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.sql.SQLException;

public class Neo4jNamedEntityRepository implements NamedEntityRepository {
    private final JdbcTemplate jdbcTemplate;

    public Neo4jNamedEntityRepository() throws SQLException {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:neo4j:bolt://neo4j/?user=neo4j&password=dev&flatten=-1"));
        jdbcTemplate.update("CREATE CONSTRAINT ON (doc:Document) ASSERT doc.id IS UNIQUE");
        jdbcTemplate.update("CREATE CONSTRAINT ON (ne:NamedEntity) ASSERT ne.id IS UNIQUE");
    }

    @Override
    public NamedEntity get(String id) {
        return (NamedEntity) jdbcTemplate.queryForObject(
                "MATCH (ne:NamedEntity {id: ?})-[r:IS_MENTIONED]->(doc)\n" +
                        "RETURN ne, doc",
                new Object[]{id},
                (rs, i) ->
                        NamedEntity.create(NamedEntity.Category.parse(rs.getString("ne.category")),
                                rs.getString("ne.mention"),
                                rs.getInt("ne.offset"),
                                rs.getString("doc.id"),
                                Pipeline.Type.valueOf(rs.getString("ne.pipeline")),
                                Language.parse(rs.getString("ne.language"))));
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
                        "MERGE (ne:NamedEntity {id: ?})\n" +
                        "SET ne.category = ?,\n" +
                        "    ne.mention = ?,\n" +
                        "    ne.pipeline = ?,\n" +
                        "    ne.language = ?,\n" +
                        "    ne.offset = ?\n" +
                        "CREATE (ne)-[rel:IS_MENTIONED]->(doc)" +
                        "RETURN ne",
                new Object[]{
                        ne.getDocumentId(),
                        ne.getId(),
                        ne.getCategory().toString(),
                        ne.getMention(),
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
}
