package org.icij.datashare.utils;

import net.codestory.http.payload.Payload;

import java.util.Collections;
import java.util.Map;

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
}
