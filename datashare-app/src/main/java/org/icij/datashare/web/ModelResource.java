package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;
import org.icij.datashare.model.UnknownTargetModel;
import org.icij.datashare.utils.PayloadFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Prefix("/api/models")
public class ModelResource {
    private final Map<String, String> ontologies = new ConcurrentHashMap<>();

    @Operation(description = """
            Returns the ontology of a data model: every type it declares, with the label, the featured and \
            caption properties a client renders, and per property its label, its range (the type it points at, \
            null for a literal) and whether it is a stub.
            """,
            parameters = {@Parameter(name = "name", description = "the data model, e.g. `ftm`", in = ParameterIn.PATH),
                    @Parameter(name = "version", description = "the ontology version, the bundled one by default",
                            in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the ontology as an `Ontology` object")
    @ApiResponse(responseCode = "404", description = "if no such model, or datashare bundles no such version of it")
    @ApiResponse(responseCode = "500", description = "if the model is known but its bundled ontology cannot be read")
    @Get("/:name?version=:version")
    public Payload getOntology(String name, String version) {
        TargetModel model;
        try {
            model = TargetModelRegistry.get(name);
        } catch (UnknownTargetModel e) {
            return PayloadFormatter.error(e, HttpStatus.NOT_FOUND);
        }
        if (version != null && !version.isBlank() && !version.equals(model.version())) {
            return PayloadFormatter.error(
                    "unknown version '%s' of model '%s', datashare bundles %s".formatted(version, model.name(),
                                                                                         model.version()),
                    HttpStatus.NOT_FOUND);
        }
        return PayloadFormatter.json(ontologies.computeIfAbsent(model.name(), ignored -> write(model)));
    }

    // A model's ontology is a bundled resource, so the body cannot change while the process lives.
    private static String write(TargetModel model) {
        try {
            return JsonObjectMapper.writeValueAsString(new Ontology(model.name(), model.version(), model.types()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write the ontology of model '" + model.name() + "'", e);
        }
    }

    public record Ontology(String name, String version, Map<String, EntityType> types) {}
}
