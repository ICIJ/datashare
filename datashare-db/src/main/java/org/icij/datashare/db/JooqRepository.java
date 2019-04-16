package org.icij.datashare.db;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

import java.util.List;

public class JooqRepository implements Repository {

    @Override
    public NamedEntity get(String id) {
        return null;
    }

    @Override
    public void create(List<NamedEntity> neList) {

    }

    @Override
    public void create(Document document) {

    }

    @Override
    public void update(NamedEntity ne) {

    }

    @Override
    public NamedEntity delete(String id) {
        return null;
    }
}
