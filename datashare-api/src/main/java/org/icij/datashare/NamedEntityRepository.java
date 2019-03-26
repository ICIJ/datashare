package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

interface NamedEntityRepository {
   NamedEntity get(String id);
   int create(Document ne);
   int create(NamedEntity ne);
   void update(NamedEntity ne);
   NamedEntity delete(String id);
}
