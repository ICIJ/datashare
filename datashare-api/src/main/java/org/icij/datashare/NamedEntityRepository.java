package org.icij.datashare;

import org.icij.datashare.text.NamedEntity;

interface NamedEntityRepository {
   NamedEntity get(String id);
   void create(NamedEntity ne);
   void update(NamedEntity ne);
    NamedEntity delete(String id);
}
