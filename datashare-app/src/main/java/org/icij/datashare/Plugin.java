package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URL;
import java.util.Objects;

public class Plugin {
    public final String id;
    public final String name;
    public final String description;
    public final URL url;

    @JsonCreator
    public Plugin(@JsonProperty("id") String id,
                  @JsonProperty("name") String name,
                  @JsonProperty("description") String description,
                  @JsonProperty("url") URL url) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.url = url;
    }

    @Override
    public String toString() {
        return "Plugin id='" + id + '\'' + ", name='" + name + '\'' + ", description='" + description + '\'' + ", url=" + url;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Plugin)) return false;
        Plugin plugin = (Plugin) o;
        return id.equals(plugin.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
