package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.DocumentUserRecommendation;
import org.icij.datashare.Repository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.utils.PayloadFormatter;

import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;

@Singleton
@Prefix("/api/document-user-recommendation")
public class DocumentUserRecommendationResource {
    private final Repository repository;

    List<Project> getUserProjects(User user) {
        DatashareUser datashareUser = (DatashareUser) user;
        List<String> projectNames = datashareUser.getProjectNames();
        return repository.getProjects(projectNames);
    }

    @Inject
    public DocumentUserRecommendationResource(Repository repository) {
        this.repository = repository;
    }

    @Operation(description = "Preflight options request")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and GET")
    @Options("/")
    public Payload options() {
        return PayloadFormatter.allowMethods("OPTIONS", "GET");
    }

    @Operation(description = "Gets all user's document recommendations.",
                parameters = {@Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                              @Parameter(name = "size", description = "if not provided, the 50 first record from the \"from\" parameter", in = ParameterIn.QUERY),
                              @Parameter(name = "project", description = "if not provided, return every recommendations for every project", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the user's document recommendations", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "if either `from` or `size` are present and cannot be parsed")
    @Get("/")
    public Payload get(Context context) {
        String projectName = context.get("project");
        // Filter the list of target projects by
        // the given project (if any) in query parameters.
        List<Project> projects = getUserProjects(context.currentUser())
                .stream()
                .filter(project -> projectName == null || project.name.equals(projectName))
                .collect(Collectors.toList());
        try {
            int from = parseInt(ofNullable(context.get("from")).orElse("0"));
            int size = parseInt(ofNullable(context.get("size")).orElse("50"));
            List<DocumentUserRecommendation> recommendations = repository.getDocumentUserRecommendations(from, size, projects);
            return PayloadFormatter.json(recommendations);
        } catch (NumberFormatException e) {
            return PayloadFormatter.error("Wrong pagination parameters (`from` or `size`).", HttpStatus.BAD_REQUEST);
        }
    }
}
