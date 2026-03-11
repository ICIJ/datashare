package org.icij.datashare.text;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public enum ContentTypeCategory {

    AUDIO,
    VIDEO,
    DOCUMENT,
    EMAIL,
    IMAGE,
    PRESENTATION,
    SPREADSHEET,
    OTHER;

    public static ContentTypeCategory fromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return OTHER;
        if (contentType.startsWith("audio/")) return AUDIO;
        if (contentType.startsWith("video/")) return VIDEO;
        if (contentType.startsWith("image/")) return IMAGE;
        return specificContentTypeMapping.getOrDefault(contentType, OTHER);
    }

    private static final Map<String, ContentTypeCategory> specificContentTypeMapping = loadMapping();

    private static Map<String, ContentTypeCategory> loadMapping() {
        Properties props = new Properties();
        try (InputStream is = ContentTypeCategory.class.getResourceAsStream("/content-type-categories.properties")) {
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load content-type-categories.properties", e);
        }
        Map<String, ContentTypeCategory> map = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, ContentTypeCategory.valueOf(props.getProperty(key)));
        }
        return Collections.unmodifiableMap(map);
    }
}
