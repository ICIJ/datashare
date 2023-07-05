package org.icij.datashare.utils;

import net.codestory.http.payload.Payload;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.codestory.http.payload.Payload.ok;

public class PayloadFormatter {

    public Payload error(String message, int status) {
        Map<String, String> responseBody = Collections.singletonMap("error", message);
        return new Payload(responseBody).withCode(status);
    }

    public Payload error(RuntimeException error, int status) {
        String message = error.getMessage();
        return error(message, status);
    }

    public Payload json(String responseBody) {
        return new Payload("application/json", responseBody);
    }

    public Payload allowMethods(String... methodOrMethods) {
        List<String> methods = Arrays.stream(methodOrMethods)
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .collect(Collectors.toList());
        return ok().withAllowMethods(methods.toArray(new String[0]));
    }
}
