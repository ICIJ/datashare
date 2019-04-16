package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

import java.sql.SQLException;
import java.util.List;

interface NamedEntityRepository {
   NamedEntity get(String id) throws SQLException;
   void create(List<NamedEntity> ne) throws SQLException;
   void create(Document doc) throws SQLException;
   void update(NamedEntity ne);
    NamedEntity delete(String id) throws SQLException;
}
