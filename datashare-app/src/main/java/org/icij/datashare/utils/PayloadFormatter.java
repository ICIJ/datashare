package org.icij.datashare.utils;

import net.codestory.http.payload.Payload;

import java.util.Collections;
import java.util.Map;

public class PayloadFormatter {

    public Payload error(String message, int status) {
        Map<String, String> body = Collections.singletonMap("error", message);
        return new Payload(body).withCode(status);
    }
}
