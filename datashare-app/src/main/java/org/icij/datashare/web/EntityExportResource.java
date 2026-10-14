package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
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
import org.apache.commons.io.output.CountingOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.UncheckedIOException;
import org.icij.datashare.model.EntityExporter;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.model.UnknownTargetModel;
import org.icij.datashare.utils.PayloadFormatter;
import static org.icij.datashare.web.errors.ForbiddenException.requireGranted;

@Singleton
@Prefix("/api")
public class EntityExportResource {
    private static final Logger logger = LoggerFactory.getLogger(EntityExportResource.class);
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
    @Get("/entities/:project/export?model=:model")
    public Payload export(String project, String model, Context context) {
        requireGranted(context, project);
        EntityExporter exporter;
        try {
            exporter = EntityExporter.forModel(
                    model == null || model.isBlank() ? EntityExporter.DEFAULT_MODEL : model);
        } catch (UnknownTargetModel e) {
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
        StreamingOutput body = out -> {
            CountingOutputStream sent = new CountingOutputStream(out);
            try {
                exporter.export(statements, project, sent);
            } catch (UncheckedIOException e) {
                // fluent-http only recognises a client hanging up when it gets the bare IOException
                throw e.getCause();
            } catch (RuntimeException e) {
                // once a byte is out the response is committed, and fluent-http would append its
                // HTML error page to the entities the client already holds: truncate instead
                if (sent.getByteCount() == 0) {
                    throw e;
                }
                logger.error("entity export of project {} failed after {} bytes, the download is truncated", project,
                             sent.getByteCount(), e);
            }
        };
        return new Payload(exporter.mediaType(), body).withHeader("Content-Disposition",
                                                                  "attachment;filename=\"%s.%s.json\"".formatted(
                                                                          project, exporter.model()));
    }
}
