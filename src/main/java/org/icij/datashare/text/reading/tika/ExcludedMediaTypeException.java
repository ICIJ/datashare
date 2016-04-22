package org.icij.datashare.text.reading.tika;

import org.apache.tika.exception.TikaException;

/**
 * Created by julien on 3/31/16.
 */
public class ExcludedMediaTypeException extends TikaException {

    public ExcludedMediaTypeException(String message) {
        super(message);
    }

    public ExcludedMediaTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
