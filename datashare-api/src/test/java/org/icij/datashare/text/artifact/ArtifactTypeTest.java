package org.icij.datashare.text.artifact;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class ArtifactTypeTest {
    @Test
    public void test_token_is_stable_lowercase_name() {
        assertThat(ArtifactType.RAW.token()).isEqualTo("raw");
        assertThat(ArtifactType.STRUCTURE.token()).isEqualTo("structure");
    }

    @Test
    public void test_from_token_is_case_insensitive_and_trimmed() {
        assertThat(ArtifactType.fromToken("raw")).isEqualTo(ArtifactType.RAW);
        assertThat(ArtifactType.fromToken("  RAW  ")).isEqualTo(ArtifactType.RAW);
        assertThat(ArtifactType.fromToken("Structure")).isEqualTo(ArtifactType.STRUCTURE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_from_token_rejects_unknown() {
        ArtifactType.fromToken("bogus");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_from_token_rejects_null() {
        ArtifactType.fromToken(null);
    }

    @Test
    public void test_tokens_lists_all_known_types() {
        assertThat(ArtifactType.tokens()).contains("raw").contains("structure");
    }
}
