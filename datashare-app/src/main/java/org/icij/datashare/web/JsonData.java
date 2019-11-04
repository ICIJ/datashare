package org.icij.datashare.web;

import java.util.Map;

public class JsonData {
    private Map<String, Object> data;
    public Object get(String key) {
        return data.get(key);
    }

    public boolean asBoolean(String key) {
        return (boolean) get(key);
    }
}
