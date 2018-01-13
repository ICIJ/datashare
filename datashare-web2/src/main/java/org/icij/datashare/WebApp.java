package org.icij.datashare;

import net.codestory.http.Configuration;
import net.codestory.http.WebServer;

public class WebApp {
    public static void main(String[] args) {
        new WebServer().configure(getConfiguration()).start();
    }

    static Configuration getConfiguration() {
        return routes -> routes
                .get("/", "Datashare REST API")
                .add(new ProcessResource());
    }
}