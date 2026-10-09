package org.icij.datashare.tray;

public class TrayUnavailableException extends RuntimeException {
    public TrayUnavailableException(Throwable cause) {
        super(cause.toString(), cause);
    }
}
