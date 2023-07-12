package org.icij.datashare.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.swagger.FluentReader;

import static org.icij.swagger.ClassUtils.findAllClassesUsingClassLoader;

@Singleton
@Prefix("/api/openapi")
public class OpenApiResource {
    @Get()
    public Payload get() {
        final OpenAPI openAPI = new FluentReader().read(findAllClassesUsingClassLoader(getClass().getPackageName()));
        return new Payload("text/json", Json31.mapper().convertValue(openAPI, ObjectNode.class).toString());
    }
}
