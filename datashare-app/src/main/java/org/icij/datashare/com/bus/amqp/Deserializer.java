package org.icij.datashare.com.bus.amqp;

public interface Deserializer<T> {
    T deserialize(byte[] rawJson);
}