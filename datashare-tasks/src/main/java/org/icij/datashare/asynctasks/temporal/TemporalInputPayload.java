package org.icij.datashare.asynctasks.temporal;


import java.io.Serializable;
import java.util.Map;

public record TemporalInputPayload(Map<String, Object> args) implements Serializable {
}
