package org.icij.datashare.text.artifact;

import org.junit.Test;
import java.util.List;
import java.util.stream.Collectors;
import static org.fest.assertions.Assertions.assertThat;

public class ArtifactRegistryTest {
    static class FakeArtifact implements Artifact {
        final ArtifactType type;

        FakeArtifact(ArtifactType type) {
            this.type = type;
        }

        public ArtifactType type() {
            return type;
        }

        public java.util.Map<String, Object> taskInput() {
            return java.util.Map.of("type", type.token());
        }

        public ManifestEntry produce(ArtifactContext c) {
            return ManifestEntry.empty(taskInput());
        }
    }

    private List<String> types(List<Artifact> a) {
        return a.stream().map(artifact -> artifact.type().token()).collect(Collectors.toList());
    }

    private ArtifactRegistry registry() {
        return new ArtifactRegistry(List.of(new FakeArtifact(ArtifactType.RAW), new FakeArtifact(ArtifactType.STRUCTURE)));
    }

    @Test
    public void test_bare_returns_all() {
        assertThat(types(registry().select(null))).containsOnly("raw", "structure");
        assertThat(types(registry().select(""))).containsOnly("raw", "structure");
        assertThat(types(registry().select("   "))).containsOnly("raw", "structure");
        assertThat(types(registry().select("true"))).containsOnly("raw", "structure");
    }

    @Test
    public void test_csv_subset_case_insensitive() {
        assertThat(types(registry().select(" RAW "))).containsOnly("raw");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_comma_only_throws() {
        registry().select(",");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_double_comma_throws() {
        registry().select(",,");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_unknown_token_throws() {
        registry().select("raw,bogus");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_known_type_without_registered_producer_throws() {
        // structure is a known type, but this process only wired a raw producer, so it cannot be asked to produce it.
        new ArtifactRegistry(List.of(new FakeArtifact(ArtifactType.RAW))).select("structure");
    }
}
