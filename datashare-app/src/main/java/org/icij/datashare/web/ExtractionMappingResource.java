package org.icij.datashare.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.policies.MappingPolicy;
import org.icij.datashare.policies.Policy;
import org.icij.datashare.policies.Role;
import org.icij.datashare.tabular.DuplicateExtractionMapping;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingReader;
import org.icij.datashare.tabular.ExtractionMappingService;
import org.icij.datashare.tabular.UnknownExtractionMapping;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.PayloadFormatter;
import org.icij.datashare.web.errors.ForbiddenException;
import java.io.IOException;
import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.notFound;

@Singleton
@Prefix("/api")
public class ExtractionMappingResource {
    private final ExtractionMappingService mappings;

    @Inject
    public ExtractionMappingResource(ExtractionMappingService mappings) {
        this.mappings = mappings;
    }

    @Operation(description = """
            Saves an extraction mapping. A mapping is immutable: to change one, save it again under a new id.
            The project and id come from the path and the author from the session, whatever the body says.
            """, parameters = {@Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
            @Parameter(name = "id", description = "the mapping id", in = ParameterIn.PATH)})
    @ApiResponse(responseCode = "201", description = "if the mapping was saved")
    @ApiResponse(responseCode = "400", description = "if the body is not a valid mapping")
    @ApiResponse(responseCode = "409", description = "if the project already holds a mapping with this id")
    @Put("/:project/extraction-mappings/:id")
    @Policy(idParam = "project", role = Role.PROJECT_MEMBER)
    public Payload saveMapping(String project, String id, Context context) {
        User user = (User) context.currentUser();
        try {
            ExtractionMapping mapping = read(context.request().contentAsBytes(), project, id, user.id);
            mappings.save(mapping);
            return created();
        } catch (DuplicateExtractionMapping e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.CONFLICT);
        } catch (IOException | IllegalArgumentException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Starts an extraction of a saved mapping. Poll `GET /api/task/:id` for its status.",
            parameters = {@Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "mappingId", description = "the mapping id", in = ParameterIn.PATH)})
    @ApiResponse(responseCode = "201", description = "returns the id of the started task")
    @ApiResponse(responseCode = "403",
            description = "if the user is not granted the project, or is neither the mapping's author nor a project admin")
    @ApiResponse(responseCode = "404", description = "if the project holds no mapping with this id")
    @ApiResponse(responseCode = "409",
            description = "if the stored mapping no longer reads, e.g. its target model is gone")
    @Post("/task/structuredEntityExtraction/:project/:mappingId")
    @MappingPolicy
    public Payload runMapping(String project, String mappingId, Context context) throws IOException {
        // the task refuses a user the project is not granted to, so a 201 would hand out a run bound to fail
        ForbiddenException.requireGranted(context, project);
        try {
            String taskId = mappings.run(project, mappingId, (User) context.currentUser());
            return new JsonPayload(201, new TaskResource.TaskResponse(taskId));
        } catch (UnknownExtractionMapping e) {
            return notFound();
        } catch (UnreadableExtractionMapping e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.CONFLICT);
        }
    }

    private static ExtractionMapping read(byte[] body, String project, String id, String userId) throws IOException {
        if (!(JsonObjectMapper.readTree(body) instanceof ObjectNode node)) {
            throw new IllegalArgumentException("the body must be a JSON object");
        }
        node.put("id", id).put("projectId", project).put("userId", userId);
        return ExtractionMappingReader.read(node);
    }
}
