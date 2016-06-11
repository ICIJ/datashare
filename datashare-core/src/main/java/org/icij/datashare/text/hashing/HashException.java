package org.icij.datashare.text.hashing;

/**
 * Hash exception wrapper
 *
 * Created by julien on 5/3/16.
 */
public class HashException extends Exception {

    public HashException(String message) {
        super(message);
    }

    public HashException(String message, Throwable cause) {
        super(message, cause);
    }

}
