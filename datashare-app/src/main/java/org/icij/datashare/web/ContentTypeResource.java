package org.icij.datashare.web;

import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.text.ContentTypeCategory;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

@Singleton
@Prefix("/api/contentType")
public class ContentTypeResource {

    @Operation(description = "Returns the list of contentTypes in parameter grouped by contentTypeCategories",
            parameters = {
                    @Parameter(name = "contentTypes", description = "The list of contentTypes to group", in = ParameterIn.QUERY),
            }
    )
    @ApiResponse(responseCode = "200", description = "The list of contentTypes was successfully grouped", useReturnTypeSchema = true)
    @Post("/categories")
    public Map<ContentTypeCategory, List<String>> groupByCategories(List<String> contentTypes) {
        return contentTypes.stream().collect(groupingBy(ContentTypeCategory::fromContentType));
    }
}
