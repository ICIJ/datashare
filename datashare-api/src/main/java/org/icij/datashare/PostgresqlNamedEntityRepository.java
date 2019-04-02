package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import static org.icij.datashare.json.JsonObjectMapper.getJsonAsString;
import static org.icij.datashare.json.JsonObjectMapper.getObject;

public class PostgresqlNamedEntityRepository implements NamedEntityRepository {
    private static final String NAMED_ENTITY = "named_entity";
    private static final String DOCUMENT = "document";
    private final JdbcTemplate jdbcTemplate;

    public PostgresqlNamedEntityRepository() {
        Properties props = new Properties();
        props.setProperty("user","datashare");
        props.setProperty("password","dev");
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://postgresql/datashare", props));
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS document (doc_id VARCHAR(96) PRIMARY KEY, json jsonb NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS named_entity (doc_id VARCHAR(96) NOT NULL, json jsonb NOT NULL)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS doc_index_named_entity ON named_entity (doc_id)");
    }

    @Override
    public Document get(String id) {
        Document document = (Document) jdbcTemplate.queryForObject("SELECT json FROM document WHERE doc_id = ?",
                new Object[]{id}, (rs, i) -> getObject(rs.getString("json"), Document.class));
        List neList = jdbcTemplate.query("SELECT json FROM named_entity WHERE doc_id = ?",
                new Object[]{id}, (rs, i) -> getObject(rs.getString("json"), NamedEntity.class));
        document.addAll(neList);
        return document;
    }

    @Override
    public void create(List<NamedEntity> neList) {
        String sqlQuery = "INSERT INTO named_entity (doc_id, json) VALUES ";
        sqlQuery += String.join(",", Collections.nCopies(neList.size(), "(?, cast(? AS JSONB))"));
        List<Object> parameters = new LinkedList<>();
        for (NamedEntity ne : neList) {
            parameters.add(ne.getDocumentId());
            parameters.add(getJsonAsString(ne));
        }
        jdbcTemplate.update(sqlQuery, parameters.toArray());
    }

    @Override
    public void create(Document document) throws SQLException {
        jdbcTemplate.update("INSERT INTO document (doc_id, json) VALUES (?, cast(? AS JSONB))", new Object[] {document.getId(), getJsonAsString(document)});
    }

    @Override
    public void update(NamedEntity ne) {

    }

    @Override
    public NamedEntity delete(String id) {
        return null;
    }
}
