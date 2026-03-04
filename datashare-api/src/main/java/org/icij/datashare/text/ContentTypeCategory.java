package org.icij.datashare.text;

import java.util.Map;

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
        if(contentType == null || contentType.isBlank()) {
            return OTHER;
        }
        if (contentType.startsWith("audio/")) {
            return ContentTypeCategory.AUDIO;
        } else if (contentType.startsWith("video/")) {
            return ContentTypeCategory.VIDEO;
        } else if (contentType.startsWith("image/")) {
            return ContentTypeCategory.IMAGE;
        }
        return specificContentTypeMapping.getOrDefault(contentType, ContentTypeCategory.OTHER);
    }

    private static final Map<String, ContentTypeCategory> specificContentTypeMapping = Map.ofEntries(
            Map.entry("application/mp4", ContentTypeCategory.VIDEO),
            Map.entry("application/pdf", ContentTypeCategory.DOCUMENT),
            Map.entry("application/msword", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.wordperfect", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ContentTypeCategory.DOCUMENT),
            Map.entry("application/xml", ContentTypeCategory.DOCUMENT),
            Map.entry("text/plain", ContentTypeCategory.DOCUMENT),
            Map.entry("application/rtf", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.ms-word.document.macroenabled.12", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.oasis.opendocument.text", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.ms-word2006ml", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.template", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.ms-wordml", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.ms-word.template.macroenabled.12", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.ms-works", ContentTypeCategory.DOCUMENT),
            Map.entry("application/msword.document", ContentTypeCategory.DOCUMENT),
            Map.entry("application/vnd.ms-outlook", ContentTypeCategory.EMAIL),
            Map.entry("message/rfc822", ContentTypeCategory.EMAIL),
            Map.entry("application/vnd.ms-outlook-pst", ContentTypeCategory.EMAIL),
            Map.entry("application/x-corelpresentations", ContentTypeCategory.PRESENTATION),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ContentTypeCategory.PRESENTATION),
            Map.entry("application/vnd.ms-powerpoint", ContentTypeCategory.PRESENTATION),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.slideshow", ContentTypeCategory.PRESENTATION),
            Map.entry("application/vnd.ms-powerpoint.presentation.macroenabled.12", ContentTypeCategory.PRESENTATION),
            Map.entry("application/vnd.ms-powerpoint.slideshow.macroenabled.12", ContentTypeCategory.PRESENTATION),
            Map.entry("application/vnd.ms-excel", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/vnd.ms-excel.sheet.macroenabled.12", ContentTypeCategory.SPREADSHEET),
            Map.entry("text/csv", ContentTypeCategory.SPREADSHEET),
            Map.entry("text/tsv", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/vnd.ms-excel.sheet.binary.macroenabled.12", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/x-tika-msworks-spreadsheet", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/vnd.ms-spreadsheetml", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/vnd.ms-excel.sheet.4", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/x-msexcel", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/vnd.ms-excel.template.macroenabled.12", ContentTypeCategory.SPREADSHEET),
            Map.entry("application/vnd.ms-excel.sheet.3", ContentTypeCategory.SPREADSHEET));
}
