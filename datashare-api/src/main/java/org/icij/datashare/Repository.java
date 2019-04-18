package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

import java.sql.SQLException;
import java.util.List;

public interface Repository {
   NamedEntity getNamedEntity(String id);
   Document getDocument(String id) throws SQLException;
   void create(List<NamedEntity> neList);
   void create(Document document) throws SQLException;
   void update(NamedEntity ne);
   NamedEntity delete(String id);
}
