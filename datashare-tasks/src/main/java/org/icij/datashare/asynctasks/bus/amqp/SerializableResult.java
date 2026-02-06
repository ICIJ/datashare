package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
public record SerializableResult<V extends Serializable>(@JsonProperty("value") V value) implements Serializable {
}
