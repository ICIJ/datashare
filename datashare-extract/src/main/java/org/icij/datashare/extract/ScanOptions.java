package org.icij.datashare.extract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public record ScanOptions(boolean includeOSFiles, boolean includeHiddenFiles, List<String> includePatterns, List<String> excludePatterns, boolean followSymlinks, int maxDepth) {
    /**
     * Reads the options of a run, under the singular pattern keys the CLI and the old scanner use. A given-or-not
     * option needs a wrapper type: an absent one keeps the default instead of the primitive's false or 0. A pattern
     * is one string or a list, and is not split on commas (a glob such as {a,b} contains some).
     */
    @JsonCreator
    public ScanOptions(@JsonProperty("includeOSFiles") Boolean includeOSFiles,
                       @JsonProperty("includeHiddenFiles") Boolean includeHiddenFiles,
                       @JsonProperty("includePattern") Object includePattern,
                       @JsonProperty("excludePattern") Object excludePattern,
                       @JsonProperty("followSymlinks") Boolean followSymlinks,
                       @JsonProperty("maxDepth") Integer maxDepth) {
        this(includeOSFiles == null ? DEFAULTS.includeOSFiles : includeOSFiles,
             includeHiddenFiles == null ? DEFAULTS.includeHiddenFiles : includeHiddenFiles, patterns(includePattern),
             patterns(excludePattern), followSymlinks == null ? DEFAULTS.followSymlinks : followSymlinks,
             maxDepth == null ? DEFAULTS.maxDepth : maxDepth);
    }

    private static List<String> patterns(Object value) {
        if (value == null) {
            return List.of();
        }
        return value instanceof Collection<?> values ? values.stream().map(Object::toString).toList() :
               List.of(value.toString());
    }

    private static final Builder DEFAULTS = new Builder();

    public static ScanOptions defaultValues() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean includeOSFiles = false;
        private boolean includeHiddenFiles = true;
        private final List<String> includePatterns = new ArrayList<>();
        private final List<String> excludePatterns = new ArrayList<>();
        private boolean followSymlinks = false;
        private int maxDepth = Integer.MAX_VALUE;

        private Builder() {}

        public Builder includeOSFiles(boolean includeOSFiles) {
            this.includeOSFiles = includeOSFiles;
            return this;
        }

        public Builder includeHiddenFiles(boolean includeHiddenFiles) {
            this.includeHiddenFiles = includeHiddenFiles;
            return this;
        }

        public Builder includePatterns(List<String> includePatterns) {
            this.includePatterns.clear();
            this.includePatterns.addAll(includePatterns);
            return this;
        }

        public Builder includePattern(String pattern) {
            this.includePatterns.add(pattern);
            return this;
        }

        public Builder excludePatterns(List<String> excludePatterns) {
            this.excludePatterns.clear();
            this.excludePatterns.addAll(excludePatterns);
            return this;
        }

        public Builder excludePattern(String pattern) {
            this.excludePatterns.add(pattern);
            return this;
        }

        public Builder followSymlinks(boolean followSymlinks) {
            this.followSymlinks = followSymlinks;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public ScanOptions build() {
            return new ScanOptions(includeOSFiles, includeHiddenFiles, List.copyOf(includePatterns),
                                   List.copyOf(excludePatterns), followSymlinks, maxDepth);
        }

    }
}
