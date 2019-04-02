package org.icij.datashare;

import com.mongodb.DB;
import com.mongodb.MongoClient;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.mongojack.DBQuery;
import org.mongojack.DBUpdate;
import org.mongojack.JacksonDBCollection;

import java.util.List;

public class MongoDBNamedEntityRepository implements NamedEntityRepository {
    private final DB mongoDb;

    public MongoDBNamedEntityRepository() {
        mongoDb = new MongoClient("mongodb").getDB("datashare");
    }

    @Override
    public Document get(String id) {
        return JacksonDBCollection.wrap(mongoDb.getCollection("document"), Document.class, String.class).findOne(DBQuery.is("_id", id));
    }

    @Override
    public void create(List<NamedEntity> neList) {
        JacksonDBCollection.wrap(mongoDb.getCollection("document"), Document.class, String.class).
                updateById(neList.get(0).getDocumentId(), DBUpdate.pushAll("neList", neList));
    }

    @Override
    public void create(Document document) {
        JacksonDBCollection.wrap(mongoDb.getCollection("document"), Document.class, String.class).insert(document);
    }

    @Override
    public void update(NamedEntity ne) {}

    @Override
    public NamedEntity delete(String id) {
        return null;
    }
}
