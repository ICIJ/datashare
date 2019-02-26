package org.icij.datashare;

import org.icij.datashare.text.NamedEntity;

import java.sql.SQLException;

interface NamedEntityRepository {
   NamedEntity get(String id) throws SQLException;
   void create(NamedEntity ne) throws SQLException;
   void update(NamedEntity ne);
    NamedEntity delete(String id) throws SQLException;
}
