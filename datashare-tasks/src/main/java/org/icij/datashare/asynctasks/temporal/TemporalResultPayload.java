package org.icij.datashare.asynctasks.temporal;

import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;

public record TemporalResultPayload<V extends Serializable>(@JsonValue V value) implements Serializable {
}
