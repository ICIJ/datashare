package org.icij.datashare.web;

import com.google.inject.Inject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import net.codestory.http.payload.StreamingOutput;
import java.io.UncheckedIOException;
import org.icij.datashare.model.EntityExporter;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.model.UnknownTargetModel;
import org.icij.datashare.utils.PayloadFormatter;
import static org.icij.datashare.web.errors.ForbiddenException.requireGranted;

@Prefix("/api")
public class EntityExportResource {
    private final StatementRepository statements;

    @Inject
    public EntityExportResource(StatementRepository statements) {
        this.statements = statements;
    }

    @Operation(description = """
            Downloads the structured entities of a project in the native format of their data model:
            FtM newline-delimited JSON by default.
            """, parameters = {@Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
            @Parameter(name = "model", description = "the data model to export, `ftm` by default",
                    in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "streams the entities, one per line for FtM")
    @ApiResponse(responseCode = "400", description = "if no exporter writes this data model")
    @ApiResponse(responseCode = "403", description = "if the user is not granted the project")
    @Get("/:project/entities/export?model=:model")
    public Payload export(String project, String model, Context context) {
        requireGranted(context, project);
        EntityExporter exporter;
        try {
            exporter = EntityExporter.forModel(model == null || model.isBlank() ? "ftm" : model);
        } catch (UnknownTargetModel e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
        StreamingOutput body = out -> {
            try {
                statements.entities(project, exporter.model(), entities -> {
                    exporter.export(entities, out);
                    return null;
                });
            } catch (UncheckedIOException e) {
                // fluent-http only recognises a client hanging up when it gets the bare IOException
                throw e.getCause();
            }
        };
        return new Payload(exporter.mediaType(), body).withHeader("Content-Disposition",
                                                                  "attachment;filename=\"%s.%s.json\"".formatted(
                                                                          project, exporter.model()));
    }
}
