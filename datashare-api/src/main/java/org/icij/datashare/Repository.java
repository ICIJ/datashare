package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public interface Repository {
   NamedEntity getNamedEntity(String id) throws SQLException;
   Document getDocument(String id) throws SQLException, IOException;
   void create(List<NamedEntity> neList) throws SQLException;
   void create(Document document) throws SQLException;
   NamedEntity deleteNamedEntity(String id);
   Document deleteDocument(String id);
}
