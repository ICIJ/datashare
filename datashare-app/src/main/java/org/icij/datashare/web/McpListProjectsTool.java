package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;

public class McpListProjectsTool implements McpTool {
    private final ProjectResource projectResource;

    @Inject
    public McpListProjectsTool(ProjectResource projectResource) {
        this.projectResource = projectResource;
    }

    @Override
    public String name() {
        return "list_projects";
    }

    @Override
    public String description() {
        return "Lists the Datashare projects you can access.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) {
        return projectResource.getProjects(context);
    }
}
