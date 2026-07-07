package org.icij.datashare.text;


/**
 * Reference to an indexed document inside a string {@code org.icij.extract.queue.DocumentQueue}:
 * the document id, optionally followed by its Elasticsearch routing id (root document id) for
 * embedded documents, separated by '|'. Bare ids (root documents and legacy queue entries)
 * parse to a null rootId, which keeps old in-flight entries working unchanged.
 */
public record DocReference(String id, String rootId) {
    public static final String SEPARATOR = "|";

    public static DocReference parse(String entry) {
        int separatorIndex = entry.indexOf(SEPARATOR);
        return separatorIndex < 0 ? new DocReference(entry, null) :
                new DocReference(entry.substring(0, separatorIndex), entry.substring(separatorIndex + 1));
    }

    public static DocReference fromDocument(Document document) {
        return document.isRootDocument() ? new DocReference(document.getId(), null) :
                new DocReference(document.getId(), document.getRootDocument());
    }

    public String toQueueEntry() {
        return rootId == null || rootId.equals(id) ? id : id + SEPARATOR + rootId;
    }

    public String routing() {
        return rootId == null ? id : rootId;
    }
}
