package org.icij.datashare.text.nlp.gatenlp;

import gate.Gate;
import gate.LanguageAnalyser;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;

public class GateNlpPluginsTest {

    @BeforeClass
    public static void setUp() throws Exception {
        Gate.setGateHome(new File("src/main/resources/"));
        Gate.setPluginsHome(new File("src/test/resources/models/gatenlp/8-4-1/"));
        Gate.init();
    }

    @Test
    public void test_load_annie_when_lang_is_en() {
        GateNlpPlugins.getInstance().get(ENGLISH, getClass().getClassLoader());

        assertThat(GateNlpPlugins.getInstance().isLoaded(ENGLISH)).isTrue();
        assertThat(GateNlpPlugins.getInstance().get(ENGLISH, getClass().getClassLoader()).get()).isInstanceOf(LanguageAnalyser.class);
    }

    @Test
    public void test_load_fr_lang_models() {
        GateNlpPlugins.getInstance().get(FRENCH, getClass().getClassLoader());

        assertThat(GateNlpPlugins.getInstance().isLoaded(FRENCH)).isTrue();
        assertThat(GateNlpPlugins.getInstance().get(FRENCH, getClass().getClassLoader()).get()).isInstanceOf(LanguageAnalyser.class);
    }
}