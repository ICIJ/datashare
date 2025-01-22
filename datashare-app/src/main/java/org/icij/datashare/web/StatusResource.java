package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.openmetrics.StatusMapper;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;

@Singleton
@Prefix("/api")
public class StatusResource {
    private final PropertiesProvider propertiesProvider;
    private final Repository repository;
    private final Indexer indexer;
    private final TaskManager taskManager;

    @Inject
    public StatusResource(PropertiesProvider propertiesProvider, Repository repository, Indexer indexer, TaskManager taskManager) {
        this.propertiesProvider = propertiesProvider;
        this.repository = repository;
        this.indexer = indexer;
        this.taskManager = taskManager;
    }

    @Operation(description = "Retrieve the status of databus connection, database connection and index.",
            parameters = { @Parameter(name = "format=openmetrics", description = "if provided in the URL it will return the status in openmetrics format", in = ParameterIn.QUERY) })
    @ApiResponse(responseCode = "200", description = "returns the status of datashare elements", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "504", description = "proxy error when elasticsearch is down", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", description = "service unavailable when other services are down", useReturnTypeSchema = true)
    @Get("/status")
    public Payload getStatus(Context context) throws IOException {
        Status status = new Status(repository.getHealth(), indexer.getHealth(), taskManager.getHealth());
        if ("openmetrics".equals(context.request().query().get("format"))) {
            return new Payload("text/plain;version=0.0.4",
                    new StatusMapper("datashare", status, propertiesProvider.get("platform").orElse(null)).toString());
        } else {
            return new Payload("application/json", status, status.getHttpStatus());
        }
    }

    public static class Status {
        public final boolean database;
        public final boolean index;
        public final boolean taskManager;

        Status(boolean database, boolean index, boolean taskManager) {
            this.database = database;
            this.index = index;
            this.taskManager = taskManager;
        }

        @JsonIgnore
        int getHttpStatus() {
            if (!index) {
                return HttpStatus.GATEWAY_TIMEOUT;
            } else if (!database || !taskManager) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            return HttpStatus.OK;
        }
    }
}


