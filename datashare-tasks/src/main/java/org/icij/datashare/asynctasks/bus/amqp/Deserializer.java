package org.icij.datashare.asynctasks.bus.amqp;

import java.io.IOException;

public interface Deserializer<T> {
    T deserialize(byte[] rawJson);

    class DeserializeException extends RuntimeException {
        public DeserializeException(IOException e) {
            super(e);
        }
    }
}