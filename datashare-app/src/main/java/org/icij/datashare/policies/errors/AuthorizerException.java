package org.icij.datashare.policies.errors;

public class AuthorizerException extends RuntimeException {
    public AuthorizerException(String message) {
        super(message);
    }
}
