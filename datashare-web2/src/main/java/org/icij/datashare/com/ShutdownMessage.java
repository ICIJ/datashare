package org.icij.datashare.com;

public class ShutdownMessage extends Message {
    public ShutdownMessage() {
        super(Type.SHUTDOWN);
    }
}
