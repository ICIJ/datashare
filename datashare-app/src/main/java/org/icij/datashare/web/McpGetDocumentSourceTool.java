package org.icij.datashare.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.utils.DocumentSourceAccess;
import org.icij.datashare.web.McpGetDocumentTool.DocumentAddress;
import java.util.Map;
import static org.icij.datashare.text.Project.project;

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
               "Fetch it with the same credentials as this call. The bytes never cross the MCP wire, so a large " +
               "file is streamed by the download rather than carried in this result. The download's own headers " +
               "give the content type and the length.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) {
        DocumentAddress address = DocumentAddress.from(arguments);
        Payload gated = gated(address, context);
        if (gated.code() == HttpStatus.FORBIDDEN) {
            throw new McpToolException.Forbidden(address.project());
        }
        if (gated.code() != HttpStatus.OK) {
            throw new McpToolException(refusal(gated));
        }
        return gated.rawContent();
    }

    // The gate /documents/src goes through, so a URL is only handed out for a download the caller could
    // perform: membership, the project download restriction, the root size limit, and readable bytes.
    private Payload gated(DocumentAddress address, Context context) {
        try {
            return sources.gated(address.project(), address.id(), address.routing(), context, document -> {
                if (!sources.isSourceAvailable(document, project(address.project()))) {
                    throw new McpToolException.NotFound(address.id());
                }
                return new Payload(Map.of("url", url(address)));
            });
        } catch (NotFoundException e) {
            throw new McpToolException.NotFound(address.id(), e);
        } catch (ElasticsearchException e) {
            if ("index_not_found_exception".equals(McpGetDocumentTool.indexNotFound(e))) {
                throw new McpToolException.NotFound(address.project(), e);
            }
            throw e;
        }
    }

    // A path rather than an absolute URL: behind a reverse proxy the server has no reliable view of its
    // own public origin, and the client already knows the one it reached this tool on.
    private static String url(DocumentAddress address) {
        String url = "/api/%s/documents/src/%s".formatted(address.project(), address.id());
        return address.routing() == null ? url : url + "?routing=" + address.routing();
    }

    // The gate's own wording, so a refusal cannot drift from the one /documents/src gives for the same state.
    private static String refusal(Payload gated) {
        return String.valueOf(((Map<?, ?>) gated.rawContent()).get("error"));
    }
}
