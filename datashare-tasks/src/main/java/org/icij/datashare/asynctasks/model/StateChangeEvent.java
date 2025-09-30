package org.icij.datashare.asynctasks.model;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Valid
public class StateChangeEvent {

    @NotNull private String type;

    private Map<String, Object> payload;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "StateChangeEvent{" + "type='" + type + '\'' + ", payload=" + payload + '}';
    }
}
