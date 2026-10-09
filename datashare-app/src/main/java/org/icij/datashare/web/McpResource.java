package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.policies.TaskPolicyAnnotation;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.TaskFinder;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.ExtractedText;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.IndexAccessVerifier;
import org.icij.datashare.web.errors.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Singleton
@Prefix("/api/mcp")
public class McpResource {
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(McpResource.class);
    private final List<McpTool> tools;
    private static final Map<String, Object> NO_ARGS = Map.of("type", "object", "properties", Map.of());

    @Inject
    public McpResource(ProjectResource projectResource, Indexer indexer, DocumentResource documentResource,
                       TaskFinder taskFinder, TaskManager taskManager, TaskPolicyAnnotation taskPolicy,
                       PropertiesProvider propertiesProvider) {
        this(List.of(listProjects(projectResource), searchDocuments(indexer), getDocument(documentResource),
                     listTasks(taskFinder), stopTask(taskManager, taskPolicy, propertiesProvider)));
    }

    McpResource(List<McpTool> tools) {
        this.tools = tools;
    }

    static McpTool listProjects(ProjectResource projectResource) {
        return new McpTool("list_projects", "Lists the Datashare projects you can access.", NO_ARGS,
                           (args, context) -> projectResource.getProjects(context));
    }

    static McpTool listTasks(TaskFinder taskFinder) {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string",
                                                                                                  "description",
                                                                                                  "case-insensitive pattern on the task name")));
        return new McpTool("list_tasks", "Lists the tasks you can see.", schema, (args, context) -> {
            User user = (User) context.currentUser();
            TaskFilters filters = new TaskFilters().with(Pattern.CASE_INSENSITIVE);
            String name = args.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                filters = filters.with(name);
            }
            return taskFinder.findVisibleTasksFor(user, filters).toList();
        });
    }

    static McpTool stopTask(TaskManager taskManager, TaskPolicyAnnotation taskPolicy,
                            PropertiesProvider propertiesProvider) {
        Map<String, Object> schema =
                Map.of("type", "object", "properties", Map.of("taskId", Map.of("type", "string")), "required",
                       List.of("taskId"));
        return new McpTool("stop_task", "Stops a running task.", schema, (args, context) -> {
            String taskId = requireText(args, "taskId");
            DatashareUser user = Authorizer.requireUser((DatashareUser) context.currentUser());
            Task<?> task = taskManager.getTask(taskId);
            if (Mode.SERVER.name().equals(propertiesProvider.get("mode").orElse(null)) &&
                !taskPolicy.isAllowed(user, task, Domain.DEFAULT, Role.PROJECT_ADMIN, Role.PROJECT_MEMBER)) {
                throw new ForbiddenException("forbidden");
            }
            return Map.of("taskId", taskId, "stopped", taskManager.stopTask(taskId));
        });
    }

    static McpTool searchDocuments(Indexer indexer) {
        Map<String, Object> schema = Map.of("type", "object", "properties",
                                            Map.of("project", Map.of("type", "string", "description", "project id"),
                                                   "query", Map.of("type", "string", "description",
                                                                   "Elasticsearch query_string syntax"), "size",
                                                   Map.of("type", "integer", "minimum", 1, "maximum", 50, "default",
                                                          10)), "required", List.of("project", "query"));
        return new McpTool("search_documents", "Full-text search in the documents of one project.", schema,
                           (args, context) -> {
                               String project = IndexAccessVerifier.checkIndices(requireText(args, "project"));
                               if (project.contains(",")) {
                                   throw new IllegalArgumentException("invalid argument: project");
                               }
                               String query = requireText(args, "query");
                               int size = intArg(args, "size", 10, 1, 50);
                               IndexAccessVerifier.checkPath(project + "/_search", context);
                               Map<String, Object> body =
                                       Map.of("size", size, "_source", List.of("path", "contentType"), "query",
                                              Map.of("bool",
                                                     Map.of("must", Map.of("query_string", Map.of("query", query)),
                                                            "filter", Map.of("term", Map.of("type", "Document")))),
                                              "highlight", Map.of("fields", Map.of("content", Map.of())));
                               JsonNode response = MAPPER.readTree(indexer.executeRaw("POST", project + "/_search",
                                                                                      MAPPER.writeValueAsString(body)));
                               List<Map<String, Object>> hits = new ArrayList<>();
                               for (JsonNode hit : response.at("/hits/hits")) {
                                   List<String> highlights = new ArrayList<>();
                                   hit.at("/highlight/content").forEach(fragment -> highlights.add(fragment.asText()));
                                   Map<String, Object> trimmed = new LinkedHashMap<>();
                                   trimmed.put("id", hit.path("_id").asText());
                                   trimmed.put("routing", hit.path("_routing").asText(null));
                                   trimmed.put("path", hit.at("/_source/path").asText(null));
                                   trimmed.put("contentType", hit.at("/_source/contentType").asText(null));
                                   trimmed.put("highlights", highlights);
                                   hits.add(trimmed);
                               }
                               return Map.of("total", response.at("/hits/total/value").asLong(), "hits", hits);
                           });
    }

    static McpTool getDocument(DocumentResource documentResource) {
        Map<String, Object> schema = Map.of("type", "object", "properties",
                                            Map.of("project", Map.of("type", "string"), "id",
                                                   Map.of("type", "string", "description", "document id"), "routing",
                                                   Map.of("type", "string", "description",
                                                          "root document id, for embedded documents"), "offset",
                                                   Map.of("type", "integer", "minimum", 0, "default", 0), "limit",
                                                   Map.of("type", "integer", "minimum", 1, "default", 10000)),
                                            "required", List.of("project", "id"));
        return new McpTool("get_document", "Reads a document's metadata and a slice of its extracted text.", schema,
                           (args, context) -> {
                               String project = requireText(args, "project");
                               String id = requireText(args, "id");
                               String routing = args.path("routing").asText(null);
                               Document doc = documentResource.getDoc(project, id, routing, context);
                               int length = doc.getContentTextLength();
                               int offset = Math.min(intArg(args, "offset", 0, 0, Integer.MAX_VALUE), length);
                               int limit =
                                       Math.min(intArg(args, "limit", 10000, 1, Integer.MAX_VALUE), length - offset);
                               Payload text =
                                       documentResource.getExtractedText(project, id, routing, offset, limit, null,
                                                                         context);
                               if (text.code() != 200) {
                                   throw new IllegalStateException(String.valueOf(text.rawContent()));
                               }
                               ExtractedText slice = (ExtractedText) text.rawContent();
                               Map<String, Object> result = new LinkedHashMap<>();
                               result.put("id", doc.getId());
                               result.put("path", String.valueOf(doc.getPath()));
                               result.put("contentType", doc.getContentType());
                               result.put("offset", slice.offset);
                               result.put("maxOffset", slice.maxOffset);
                               result.put("text", slice.content);
                               return result;
                           });
    }

    @Post()
    public Payload handle(Context context) throws IOException {
        JsonNode request;
        try {
            request = MAPPER.readTree(context.request().content());
        } catch (JsonProcessingException e) {
            return json(error(NullNode.getInstance(), -32700, "Parse error"));
        }
        if (request == null || !request.isObject()) {
            return json(error(NullNode.getInstance(), -32600, "Invalid Request"));
        }
        JsonNode id = request.path("id");
        String method = request.path("method").asText();
        if (method.startsWith("notifications/")) {
            return new Payload(202);
        }
        return json(switch (method) {
            case "initialize" -> result(id, Map.of("protocolVersion", PROTOCOL_VERSION, "serverInfo",
                                                   Map.of("name", "datashare", "version", version()), "capabilities",
                                                   Map.of("tools", Map.of())));
            case "tools/list" -> result(id, Map.of("tools", tools.stream()
                                                                 .map(tool -> Map.of("name", tool.name(), "description",
                                                                                     tool.description(), "inputSchema",
                                                                                     tool.inputSchema())).toList()));
            case "tools/call" -> callTool(id, request.path("params"), context);
            default -> error(id, -32601, "Method not found: " + method);
        });
    }

    private Map<String, Object> callTool(JsonNode id, JsonNode params, Context context) {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        McpTool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        try {
            return result(id, toolResult(MAPPER.writeValueAsString(tool.handler().call(args, context)), false));
        } catch (ForbiddenException | UnauthorizedException e) {
            return result(id, toolResult("forbidden: " + subject(args, "project"), true));
        } catch (NotFoundException | UnknownTask e) {
            return result(id, toolResult("not found: " + subject(args, "id"), true));
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (Exception e) {
            logger.error("MCP tool {} failed", name, e);
            return result(id, toolResult(String.valueOf(e.getMessage()), true));
        }
    }

    static String requireText(JsonNode args, String name) {
        String value = args.path(name).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing argument: " + name);
        }
        return value;
    }

    static int intArg(JsonNode args, String name, int defaultValue, int min, int max) {
        JsonNode node = args.path(name);
        if (node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt() || node.asInt() < min || node.asInt() > max) {
            throw new IllegalArgumentException("invalid argument: " + name);
        }
        return node.asInt();
    }

    private static String subject(JsonNode args, String fallback) {
        return args.has("taskId") ? args.get("taskId").asText() : args.path(fallback).asText();
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", isError);
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        return message(id, "result", result);
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        return message(id, "error", Map.of("code", code, "message", message));
    }

    private static Map<String, Object> message(JsonNode id, String key, Object value) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put(key, value);
        return message;
    }

    private static Payload json(Object body) throws IOException {
        return new Payload("application/json", MAPPER.writeValueAsString(body));
    }

    private static String version() throws IOException {
        return RootResource.getDatashareVersion().getProperty("git.build.version", "unknown");
    }
}
