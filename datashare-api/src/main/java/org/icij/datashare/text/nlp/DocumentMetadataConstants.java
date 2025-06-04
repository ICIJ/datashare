package org.icij.datashare.text.nlp;

import java.util.Locale;
import java.util.regex.Pattern;

public interface DocumentMetadataConstants {
    String DEFAULT_VALUE_UNKNOWN = "unknown";
    String DEFAULT_METADATA_FIELD_PREFIX = "tika_metadata_";
    Pattern fieldName = Pattern.compile("[^A-Za-z0-9_]");
    
    // From Tika DublinCore
    String NAMESPACE_URI_DC = "http://purl.org/dc/elements/1.1/";
    String NAMESPACE_URI_DC_TERMS = "http://purl.org/dc/terms/";
    String PREFIX_DC = "dc";
    String PREFIX_DC_TERMS = "dcterms";
    String FORMAT = "dc:format";
    String IDENTIFIER = "dc:identifier";
    String MODIFIED = "dcterms:modified";
    String CONTRIBUTOR = "dc:contributor";
    String COVERAGE = "dc:coverage";
    String CREATOR = "dc:creator";
    String CREATED = "dcterms:created";
    String DATE = "dc:date";
    String DESCRIPTION = "dc:description";
    String LANGUAGE = "dc:language";
    String PUBLISHER = "dc:publisher";
    String RELATION = "dc:relation";
    String RIGHTS = "dc:rights";
    String SOURCE = "dc:source";
    String SUBJECT = "dc:subject";
    String TITLE = "dc:title";
    String TYPE = "dc:type";
    String RESOURCE_NAME_KEY = "resourceName";
    String TIKA_VERSION = "Tika-Version";

    default String getField(String key) {
        final String normalizedName = fieldName.matcher(key).replaceAll("_").toLowerCase(Locale.ROOT);
        return DEFAULT_METADATA_FIELD_PREFIX + normalizedName;
    }
}

