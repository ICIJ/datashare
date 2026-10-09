package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** The part of an Elasticsearch search response an MCP client needs: no _source beyond path and type. */
record McpSearchResult(long total, List<McpSearchResult.SearchHit> hits) {
    static McpSearchResult from(JsonNode response) {
        List<SearchHit> hits = new ArrayList<>();
        response.at("/hits/hits").forEach(hit -> hits.add(SearchHit.from(hit)));
        return new McpSearchResult(response.at("/hits/total/value").asLong(), hits);
    }

    record SearchHit(String id, String routing, String path, String contentType, List<String> highlights) {
        static SearchHit from(JsonNode hit) {
            return new SearchHit(hit.path("_id").asText(), hit.path("_routing").asText(null),
                                 hit.at("/_source/path").asText(null), hit.at("/_source/contentType").asText(null),
                                 highlights(hit));
        }

        private static List<String> highlights(JsonNode hit) {
            List<String> highlights = new ArrayList<>();
            hit.at("/highlight/content").forEach(fragment -> highlights.add(fragment.asText()));
            return highlights;
        }
    }
}
