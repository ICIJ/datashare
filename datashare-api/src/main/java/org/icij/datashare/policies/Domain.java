package org.icij.datashare.policies;


public record Domain(String id) {
    public static Domain of(String id) {
        return new Domain(id);
    }
}
