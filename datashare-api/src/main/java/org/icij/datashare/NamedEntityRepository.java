package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import java.util.List;

interface NamedEntityRepository {
   NamedEntity get(String id);
   void create(List<NamedEntity> neList);
   void create(Document document);
   void update(NamedEntity ne);
    NamedEntity delete(String id);
}
