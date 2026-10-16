package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class McpListTagsTool implements McpTool {
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 50, 1, 500);
    private final McpIndexSearch index;

    @Inject
    public McpListTagsTool(McpIndexSearch index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "list_tags";
    }

    @Override
    public String description() {
        return "Lists the tags in use in one project, most used first. The index lowercases tags, so a tag saved as "
               + "\"Invoice\" is listed as \"invoice\". These lowercased labels are for discovery only: to pass a "
               + "tag back to tag_documents or untag_documents, read its stored casing from get_document first.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        String project = McpIndexSearch.requireSingleProject(arguments);
        JsonNode response = index.search(project, aggregationBody(SIZE.readFrom(arguments)), context);
        return TagCount.from(response.at("/aggregations/tags/buckets"));
    }

    private static Map<String, Object> aggregationBody(int size) {
        return Map.of("size", 0, "aggs", Map.of("tags", Map.of("terms", Map.of("field", "tags", "size", size))));
    }

    record TagCount(String tag, long count) {
        // A missing aggregations node iterates zero times, which is the right answer for a project with no tags.
        static List<TagCount> from(JsonNode buckets) {
            List<TagCount> tags = new ArrayList<>();
            buckets.forEach(bucket -> tags.add(
                    new TagCount(bucket.path("key").asText(), bucket.path("doc_count").asLong())));
            return tags;
        }
    }
}
