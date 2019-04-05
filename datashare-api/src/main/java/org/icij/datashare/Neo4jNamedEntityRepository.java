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
                transaction.run("CREATE CONSTRAINT ON (ne:NamedEntity) ASSERT ne.mention IS UNIQUE");
                return "";
            });
        }
    }

    @Override
    public NamedEntity get(String id) {
        try ( Session session = driver.session() ) {
            return session.readTransaction(transaction -> {
                StatementResult result = transaction.run(
                        "MATCH (ne:NamedEntity)-[rel:IS_MENTIONED {id: $id}]->(doc) " +
                        "RETURN ne, rel, doc", parameters("id", id));
                Record ne = result.next();
                return NamedEntity.create(NamedEntity.Category.parse(ne.get("rel").get("category").asString()),
                        ne.get("ne").get("mention").asString(),
                        ne.get("rel").get("offset").asInt(),
                        ne.get("doc").get("id").asString(),
                        Pipeline.Type.valueOf(ne.get("rel").get("pipeline").asString()),
                        Language.parse(ne.get("rel").get("language").asString()));
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
                                "MERGE (ne:NamedEntity {mention: $mention})\n" +
                                "MERGE (ne)-[rel:IS_MENTIONED {id: $neId}]->(doc)\n" +
                                "SET rel.category = $category,\n" +
                                "    rel.mention = $mention,\n" +
                                "    rel.pipeline = $pipeline,\n" +
                                "    rel.language = $language,\n" +
                                "    rel.offset = $offset",
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
