package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

import java.io.IOException;
import java.util.List;

public interface NamedEntityRepository {
   NamedEntity get(String id);
   void create(List<NamedEntity> neList) throws IOException;
   void create(Document document) throws IOException;
   void update(NamedEntity ne);
    NamedEntity delete(String id);
}
