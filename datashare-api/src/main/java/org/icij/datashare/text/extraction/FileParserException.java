package org.icij.datashare.text.extraction;

/**
 * Created by julien on 3/9/16.
 */
public class FileParserException extends Exception {

    public FileParserException(String message) {
        super(message);
    }

    public FileParserException(String message, Throwable cause) {
        super(message, cause);
    }

}

