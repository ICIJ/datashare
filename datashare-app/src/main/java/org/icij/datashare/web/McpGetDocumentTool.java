package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.ExtractedText;
import org.icij.datashare.web.errors.ForbiddenException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpGetDocumentTool implements McpTool {
    private static final McpArguments.IntArgument OFFSET =
            new McpArguments.IntArgument("offset", 0, 0, Integer.MAX_VALUE);
    private static final McpArguments.IntArgument LIMIT =
            new McpArguments.IntArgument("limit", 10000, 1, Integer.MAX_VALUE);
    private static final Map<String, Object> INPUT_SCHEMA = Map.of("type", "object", "properties",
                                                                   Map.of("project", Map.of("type", "string"), "id",
                                                                          Map.of("type", "string", "description",
                                                                                 "document id"), "routing",
                                                                          Map.of("type", "string", "description",
                                                                                 "root document id, for embedded documents"),
                                                                          "offset",
                                                                          Map.of("type", "integer", "minimum", 0,
                                                                                 "default", 0), "limit",
                                                                          Map.of("type", "integer", "minimum", 1,
                                                                                 "default", 10000)), "required",
                                                                   List.of("project", "id"));
    private final DocumentResource documentResource;

    @Inject
    public McpGetDocumentTool(DocumentResource documentResource) {
        this.documentResource = documentResource;
    }

    @Override
    public String name() {
        return "get_document";
    }

    @Override
    public String description() {
        return "Reads a document's metadata and a slice of its extracted text.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        DocumentAddress address = DocumentAddress.from(arguments);
        Document document = readDocument(address, context);
        ExtractedText slice = readText(address, TextWindow.within(document, arguments), context);
        return DocumentSlice.of(document, slice);
    }

    private Document readDocument(DocumentAddress address, Context context) {
        try {
            return documentResource.getDoc(address.project, address.id, address.routing, context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(address.project, e);
        } catch (NotFoundException e) {
            throw new McpToolException.NotFound(address.id, e);
        }
    }

    private ExtractedText readText(DocumentAddress address, TextWindow window, Context context) throws IOException {
        Payload text = documentResource.getExtractedText(address.project, address.id, address.routing, window.offset,
                                                         window.limit, null, context);
        if (text.code() != 200) {
            throw new McpToolException(String.valueOf(text.rawContent()));
        }
        return (ExtractedText) text.rawContent();
    }

    record DocumentAddress(String project, String id, String routing) {
        static DocumentAddress from(JsonNode arguments) {
            return new DocumentAddress(McpArguments.requireText(arguments, "project"),
                                       McpArguments.requireText(arguments, "id"),
                                       McpArguments.optionalText(arguments, "routing"));
        }
    }

    // getExtractedText rejects a window past the end of the text, so the requested one is clamped to its length.
    record TextWindow(int offset, int limit) {
        static TextWindow within(Document document, JsonNode arguments) {
            int length = document.getContentTextLength();
            int offset = Math.min(OFFSET.readFrom(arguments), length);
            return new TextWindow(offset, Math.min(LIMIT.readFrom(arguments), length - offset));
        }
    }

    record DocumentSlice(String id, String path, String contentType, int offset, int maxOffset, String text) {
        static DocumentSlice of(Document document, ExtractedText slice) {
            return new DocumentSlice(document.getId(), String.valueOf(document.getPath()), document.getContentType(),
                                     slice.offset, slice.maxOffset, slice.content);
        }
    }
}
