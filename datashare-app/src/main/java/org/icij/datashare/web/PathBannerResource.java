package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PathBanner;
import org.icij.datashare.Repository;
import org.icij.datashare.policies.Policy;
import org.icij.datashare.policies.Role;
import org.icij.datashare.session.DatashareUser;

import java.nio.file.Paths;
import java.util.List;

import static net.codestory.http.constants.HttpStatus.NO_CONTENT;
import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.web.errors.ForbiddenException.forbiddenIfNotGranted;

@Singleton
@Prefix("/api")
public class PathBannerResource {
    private final Repository repository;

    @Inject
    public PathBannerResource(Repository repository) {
        this.repository = repository;
    }

    @Operation(description = """
            Gets the list of path banners for a project and a document path.
            
            if we have on disk:
            ```
            /a/b/doc1
            /a/c/doc2
            /d/doc3
            ```
            
            And in database:
            ```
            projectId | path | note | variant | blur_sensitive_media  
            --- | --- | --- | --- | ---
            p1 | a | note A | info | false
            p1 | a/b | note B | danger | true
            ```
            
            then:
            
            - `GET /api/p1/pathBanners/a/b/doc1` will return path banner A and B
            - `GET /api/p1/pathBanners/a/c/doc2` will return path banner A
            - `GET /api/p1/pathBanners/d/doc3` will return an empty list
           
            Note the `:path:` it is a greedy parameter at the end of the url
            
            ```
            @Get("/start/with/:parameter")
            ```
            
            matches `/start/with/myparameter` but not `/start/with/myparameter/with/slashes`
            
            ```
            @Get("/start/with/:parameter:")
            ```
            
            matches `/start/with/myparameter/with/slashes` and the parameter variable will contain `myparameter/with/slashes`
            """,
            parameters = {
                @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                @Parameter(name = "path", description = "the path of the document.", in = ParameterIn.PATH),
            }
    )
    @ApiResponse(responseCode = "403", description = "if the user is not granted for the project")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Get("/:project/pathBanners/:path:")
    public List<PathBanner> getPathBanners(String project, String documentPath, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        forbiddenIfNotGranted(user.isGranted(project));
        return repository.getPathBanners(project(project), documentPath);
    }

    @Operation(description = "Gets the list of notes for a project.",
            parameters = {@Parameter(name = "project", description = "the project id", in = ParameterIn.PATH)}
    )
    @ApiResponse(responseCode = "403", description = "if the user is not granted for the project")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Get("/:project/pathBanners")
    public List<PathBanner> getProjectPathBanners(String project, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        forbiddenIfNotGranted(user.isGranted(project));
        return repository.getProjectPathBanners(project(project));
    }

    @Operation(description = "Creates or replaces a path banner for a given project and path.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "path", description = "the path the banner is attached to", in = ParameterIn.PATH),
            }
    )
    @ApiResponse(responseCode = "403", description = "if the user is not granted for the project")
    @ApiResponse(responseCode = "201", description = "if the path banner was created")
    @ApiResponse(responseCode = "200", description = "if an existing path banner was updated")
    @Put("/:project/pathBanners/:path:")
    @Policy(idParam = "project", role = Role.PROJECT_EDITOR)
    public Payload savePathBanner(String project, String documentPath, PathBanner body, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        forbiddenIfNotGranted(user.isGranted(project));
        PathBanner pathBanner = new PathBanner(project(project), Paths.get("/" + documentPath), body.note, body.variant, body.blurSensitiveMedia);
        return repository.save(pathBanner) ? created() : ok();
    }

    @Operation(description = """
            Deletes path banners for a given project and path.
            
            By default, deletes the banner whose path matches exactly.
            With `?greedy=true`, deletes every banner whose path starts with the given prefix
            (subtree delete). For example, `DELETE /api/p1/pathBanners/a/b?greedy=true` removes
            banners at `a/b`, `a/b/doc1`, `a/b/sub/doc2`, etc.
            """,
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "path", description = "the path of the banner to delete", in = ParameterIn.PATH),
                    @Parameter(name = "greedy", description = "if true, delete all banners whose path starts with the given prefix", in = ParameterIn.QUERY),
            }
    )
    @ApiResponse(responseCode = "403", description = "if the user is not granted for the project")
    @ApiResponse(responseCode = "204", description = "if the path banner(s) were deleted")
    @Delete("/:project/pathBanners/:path:")
    @Policy(idParam = "project", role = Role.PROJECT_EDITOR)
    public Payload deletePathBanner(String project, String documentPath, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        forbiddenIfNotGranted(user.isGranted(project));
        boolean isGreedy = "true".equals(context.query().get("greedy"));
        if (isGreedy) {
            repository.deleteGreedyPathBanner(project(project), documentPath);
        } else {
            repository.deletePathBanner(project(project), documentPath);
        }
        return new Payload(NO_CONTENT);
    }

    @Operation(description = "Deletes all path banners for the given project.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
            }
    )
    @ApiResponse(responseCode = "403", description = "if the user is not granted for the project")
    @ApiResponse(responseCode = "204", description = "if all path banners for the project were deleted")
    @Delete("/:project/pathBanners")
    @Policy(idParam = "project", role = Role.PROJECT_EDITOR)
    public Payload deleteProjectPathBanners(String project, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        forbiddenIfNotGranted(user.isGranted(project));
        repository.deleteProjectPathBanners(project(project));
        return new Payload(NO_CONTENT);
    }
}
