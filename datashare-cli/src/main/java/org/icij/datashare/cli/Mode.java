package org.icij.datashare.cli;

public enum Mode {
    LOCAL(true), SERVER(true), CLI(false), NER(true), BATCH_SEARCH(false), BATCH_DOWNLOAD(false), EMBEDDED(true);
    private final boolean webServer;

    Mode(boolean webServer) {
        this.webServer = webServer;
    }

    public boolean isWebServer() { return webServer;}
}
