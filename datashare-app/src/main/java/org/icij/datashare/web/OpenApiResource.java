package org.icij.datashare.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.models.OpenAPI;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.errors.BadRequestException;
import net.codestory.http.payload.Payload;
import org.icij.swagger.FluentReader;

import static java.util.Optional.ofNullable;
import static org.icij.swagger.ClassUtils.findAllClassesUsingClassLoader;

@Singleton
@Prefix("/api/openapi?format=:format")
public class OpenApiResource {
    @Operation(description = "Get the JSON or YAML OpenAPI v3 contract specification",
            parameters = {
                @Parameter(name = "format",
                    description = """
                            format of openapi description. Possible values are:
                            
                            * json (default)
                            * yaml
                            
                            """,
                    in = ParameterIn.QUERY, schema = @Schema(implementation = String.class))
            }
    )
    @ApiResponse(responseCode = "200", description="returns the JSON or YAML file")
    @Get()
    public Payload get(String format) {
        final OpenAPI openAPI = new FluentReader().read(findAllClassesUsingClassLoader(getClass().getPackageName()));
        if ("json".equals(ofNullable(format).orElse("json"))) {
            return new Payload("text/json", Json31.mapper().convertValue(openAPI, ObjectNode.class).toString());
        } else if ("yaml".equals(format)) {
            return new Payload("text/yaml", Yaml31.pretty(openAPI));
        } else {
            throw new BadRequestException();
        }
    }
}
