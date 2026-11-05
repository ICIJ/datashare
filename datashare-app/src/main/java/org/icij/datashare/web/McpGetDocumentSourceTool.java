package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.text.Document;
import org.icij.datashare.utils.DocumentSourceAccess;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class McpGetDocumentSourceTool implements McpTool {
    private final DocumentSourceAccess sources;

    @Inject
    public McpGetDocumentSourceTool(DocumentSourceAccess sources) {
        this.sources = sources;
    }

    @Override
    public String name() {
        return "get_document_source";
    }

    @Override
    public String description() {
        return "Returns the URL to download a document's original file, for a root or an embedded document. " +
               "Fetch it with the same Authorization header as this call. The bytes never cross the MCP wire, so " +
               "a large file is streamed by the download rather than carried in this result.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) {
        String project = McpArguments.requireText(arguments, "project");
        String id = McpArguments.requireText(arguments, "id");
        String routing = McpArguments.optionalText(arguments, "routing");
        // The same gate /documents/src goes through, so a URL is only ever handed out for a download
        // the caller could perform: membership, the project download restriction, the root size limit.
        Payload gated;
        try {
            gated = sources.gated(project, id, routing, context,
                                  document -> new Payload(SourceLink.of(document, project, id, routing)).withCode(
                                          HttpStatus.OK));
        } catch (NotFoundException e) {
            throw new McpToolException.NotFound(id, e);
        }
        if (gated.code() == HttpStatus.FORBIDDEN) {
            throw new McpToolException.Forbidden(project);
        }
        if (gated.code() != HttpStatus.OK) {
            throw new McpToolException("cannot download " + id + ": the file or its parent is too large");
        }
        return gated.rawContent();
    }

    record SourceLink(String url, String contentType, long contentLength) {
        // A path rather than an absolute URL: behind a reverse proxy the server has no reliable view of
        // its own public origin, and the client already knows the one it reached this tool on.
        static SourceLink of(Document document, String project, String id, String routing) {
            String url = "/api/%s/documents/src/%s".formatted(encode(project), encode(id));
            return new SourceLink(routing == null ? url : url + "?routing=" + encode(routing),
                                  document.getContentType(), document.getContentLength());
        }

        private static String encode(String segment) {
            return URLEncoder.encode(segment, StandardCharsets.UTF_8);
        }
    }
}
