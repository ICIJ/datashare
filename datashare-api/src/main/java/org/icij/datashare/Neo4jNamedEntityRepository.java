package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.neo4j.driver.v1.*;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import static org.neo4j.driver.v1.Values.parameters;

public class Neo4jNamedEntityRepository implements NamedEntityRepository {
    private final Driver driver;

    public Neo4jNamedEntityRepository() {
        driver = GraphDatabase.driver( "bolt://neo4j:7687", AuthTokens.basic( "neo4j", "dev" ) );
        try ( Session session = driver.session() ) {
            session.writeTransaction(transaction -> {
                transaction.run("CREATE CONSTRAINT ON (doc:Document) ASSERT doc.id IS UNIQUE");
                StatementResult result = transaction.run("CREATE CONSTRAINT ON (ne:NamedEntity) ASSERT ne.id IS UNIQUE");
                return "";
            });
        }
    }

    @Override
    public NamedEntity get(String id) {
        try ( Session session = driver.session() ) {
            return session.readTransaction(transaction -> {
                StatementResult result = transaction.run("MATCH (ne:NamedEntity {id: $id})-[r:IS_MENTIONED]->(doc) RETURN ne, doc", parameters("id", id));
                Record ne = result.next();
                return NamedEntity.create(NamedEntity.Category.parse(ne.get("ne").get("category").asString()),
                        ne.get("ne").get("mention").asString(),
                        ne.get("ne").get("offset").asInt(),
                        ne.get("doc").get("id").asString(),
                        Pipeline.Type.valueOf(ne.get("ne").get("pipeline").asString()),
                        Language.parse(ne.get("ne").get("language").asString()));
            });
        }
    }

    @Override
    public int create(Document document) {
        try ( Session session = driver.session() ) {
            return session.writeTransaction(transaction -> {
                StatementResult result = transaction.run("MERGE (doc:Document {id: $id}) " +
                        "SET doc.path = $path", parameters(
                        "id", document.getId(), "path", document.getPath().toString()));
                return 0;
            });
        }
    }

    @Override
    public int create(NamedEntity ne) {
        try ( Session session = driver.session() ) {
            return session.writeTransaction(transaction -> {
                StatementResult result = transaction.run("MATCH (doc:Document {id: $id})\n" +
                                "MERGE (ne:NamedEntity {id: $neId})\n" +
                                "SET ne.category = $category,\n" +
                                "    ne.mention = $mention,\n" +
                                "    ne.pipeline = $pipeline,\n" +
                                "    ne.language = $language,\n" +
                                "    ne.offset = $offset\n" +
                                "CREATE (ne)-[rel:IS_MENTIONED]->(doc)" +
                                "RETURN ne",
                        parameters("id", ne.getDocumentId(),
                                "neId", ne.getId(),
                                "category", ne.getCategory().toString(),
                                "mention", ne.getMention(),
                                "pipeline", ne.getExtractor().toString(),
                                "language", ne.getExtractorLanguage().toString(),
                                "offset", ne.getOffset()));
                return 0;
            });
        }
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
