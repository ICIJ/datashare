package org.icij.datashare.asynctasks.bus.amqp;

public interface Deserializer<T> {
    T deserialize(byte[] rawJson);
}