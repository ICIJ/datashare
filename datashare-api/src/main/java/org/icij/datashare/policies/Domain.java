package org.icij.datashare.policies;


public record Domain(String id) {
    public static Domain of(String id) {
        return new Domain(id);
    }

    // this temporary while we don't handle multiple domains
    public static final Domain DEFAULT = new Domain("default");
}
