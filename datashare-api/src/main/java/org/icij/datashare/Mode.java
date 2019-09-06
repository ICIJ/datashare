package org.icij.datashare;

public enum Mode {
    LOCAL(true), SERVER(true), CLI(false), NER(true), BATCH(false);

    private final boolean webServer;

    Mode(boolean webServer) {
        this.webServer = webServer;
    }

    public boolean isWebServer() { return webServer;}
}
