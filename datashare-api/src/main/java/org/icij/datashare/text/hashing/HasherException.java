package org.icij.datashare.text.hashing;

/**
 * Hash exception wrapper
 *
 * Created by julien on 5/3/16.
 */
public class HasherException extends Exception {

    public HasherException(String message) {
        super(message);
    }

    public HasherException(String message, Throwable cause) {
        super(message, cause);
    }

}
