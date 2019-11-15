package org.icij.datashare.web;

import java.util.Map;
import java.util.Properties;

public class JsonData {
    private Map<String, Object> data;
    public Object get(String key) {
        return data.get(key);
    }
    public Properties asProperties() {
        Properties properties = new Properties();
        properties.putAll(data);
        return properties;
    }

    public boolean asBoolean(String key) {
        return (boolean) get(key);
    }
}
