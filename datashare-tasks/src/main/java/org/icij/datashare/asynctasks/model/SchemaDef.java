package org.icij.datashare.asynctasks.model;

import jakarta.validation.constraints.NotNull;
import java.util.Date;
import java.util.Map;


public class SchemaDef implements Created {
    @NotNull
    private final String name;

    @NotNull
    private final int version;

    private final Map<String, Object> data;
    private Date createdAt;

    SchemaDef(String name, Map<String, Object> data) {
        this(name, data, 1);
    }

    SchemaDef(String name, Map<String, Object> data, int version) {
        this.name = name;
        this.data = data;
        this.version = version;
    }

    @Override
    public Date getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
