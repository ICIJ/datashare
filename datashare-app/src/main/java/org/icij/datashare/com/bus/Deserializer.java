package org.icij.datashare.com.bus;

import java.io.IOException;

public interface Deserializer<T> {
    T deserialize(byte[] rawJson) throws IOException;
}