package org.icij.datashare.extract;

import java.util.ArrayList;
import java.util.List;

public record ScanOptions(boolean includeOSFiles, boolean includeHiddenFiles, List<String> includePatterns, List<String> excludePatterns, boolean followSymlinks, int maxDepth) {
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
