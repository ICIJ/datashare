package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.Repository.Aggregate;
import org.icij.datashare.web.errors.ForbiddenException;
import java.util.List;

public class McpListTagsTool implements McpTool {
    private static final McpArguments.IntArgument SIZE =
            new McpArguments.IntArgument("size", DocumentResource.DEFAULT_TAG_COUNT_SIZE, 1,
                                         DocumentResource.MAX_TAG_COUNT_SIZE);
    private final DocumentResource documentResource;

    @Inject
    public McpListTagsTool(DocumentResource documentResource) {
        this.documentResource = documentResource;
    }

    @Override
    public String name() {
        return "list_tags";
    }

    @Override
    public String description() {
        return "Lists the tags in use in one project, most used first, with their stored casing.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) {
        String project = McpArguments.requireText(arguments, "project");
        try {
            return TagCount.from(documentResource.getProjectTagCounts(project, String.valueOf(SIZE.readFrom(arguments)),
                                                                      context));
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(project, e);
        }
    }

    record TagCount(String tag, long count) {
        static List<TagCount> from(List<Aggregate<String>> counts) {
            return counts.stream().map(a -> new TagCount(a.item, a.count)).toList();
        }
    }
}
