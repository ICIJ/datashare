package org.icij.datashare.model;

public class UnreadableModelResource extends RuntimeException {
    public final String resource;

    public UnreadableModelResource(String resource) {
        super("no data model bundled at '%s'".formatted(resource));
        this.resource = resource;
    }

    public UnreadableModelResource(String resource, Throwable root) {
        super("cannot read the data model at '%s'".formatted(resource), root);
        this.resource = resource;
    }
}
