package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

public class Plugin {
    public final String id;
    public final String name;
    public final String description;
    public final URL url;
    public final String version;

    @JsonCreator
    public Plugin(@JsonProperty("id") String id,
                  @JsonProperty("name") String name,
                  @JsonProperty("version") String version,
                  @JsonProperty("description") String description,
                  @JsonProperty("url") URL url) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.url = url;
    }

    public String getId() {return id;}

    @Override
    public String toString() {
        return "Plugin id='" + id + '\'' + '\'' + ", version='" + version + '\'' + "url=" + url;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Plugin)) return false;
        Plugin plugin = (Plugin) o;
        return id.equals(plugin.id) &&
                version.equals(plugin.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    public URL getDeliverableUrl() {
        if (url.getHost().startsWith("github.com")) {
            try {
                return new URL(url.toString() + "/archive/" + version + ".tar.gz");
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        return url;
    }
}
