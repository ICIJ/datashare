package org.icij.datashare.text.artifact;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** The closed set of artifact types datashare knows about. datashare owns this vocabulary: a type
 *  is supported only if it is declared here, and both the --artifacts selector token and the
 *  manifest.json key are the lowercase {@link #token()}. Producers may live in Java or in a Python
 *  worker, but every produced type must appear here so the backend and frontend can integrate it.
 *  Adding a type is a datashare change, by design (unknown = unsupported). */
public enum ArtifactType {
    RAW("raw"),
    STRUCTURE("structure");

    private final String token;

    ArtifactType(String token) {
        this.token = token;
    }

    /** The stable lowercase name used as the --artifacts selector token and the manifest.json key. */
    public String token() {
        return token;
    }

    /** The config-only fingerprint recorded in the manifest for this type at the given producer
     *  version. Centralised here so every producer builds the same {type, version} shape and the
     *  keys cannot drift across artifacts. */
    public Map<String, Object> taskInput(int version) {
        return Map.of("type", token, "version", version);
    }

    /** Resolve a selector/manifest token (case-insensitive, trimmed) to a type, rejecting unknown
     *  ones so "unknown = unsupported" surfaces as a clear error rather than a silent no-op. */
    public static ArtifactType fromToken(String token) {
        String normalized = token == null ? "" : token.trim().toLowerCase();
        for (ArtifactType type : values()) {
            if (type.token.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown artifact type '" + token + "'; valid types: " + tokens());
    }

    /** All known tokens, comma-separated, for error messages and --artifacts help text. */
    public static String tokens() {
        return Arrays.stream(values()).map(ArtifactType::token).collect(Collectors.joining(", "));
    }
}
