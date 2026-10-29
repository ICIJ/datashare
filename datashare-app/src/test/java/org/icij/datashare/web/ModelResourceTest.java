package org.icij.datashare.web;

import org.icij.datashare.model.TargetModelRegistry;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

public class ModelResourceTest extends AbstractProdWebServerTest {
    private static final String VERSION = TargetModelRegistry.get("ftm").version();

    @Before
    public void setUp() {
        configure(routes -> routes.add(new ModelResource()));
    }

    @Test
    public void test_serves_the_ontology_of_the_bundled_model() {
        get("/api/models/ftm").should().respond(200).haveType("application/json")
                              .contain("\"version\":\"%s\"".formatted(VERSION)).contain("\"Birth date\"");
    }

    @Test
    public void test_the_abstract_flag_uses_ftm_s_own_key() {
        get("/api/models/ftm").should().respond(200).contain("\"abstract\":false");
        get("/api/models/ftm").should().respond(200).not().contain("isAbstract");
    }

    @Test
    public void test_a_type_carries_what_the_entity_list_renders() {
        get("/api/models/ftm").should().respond(200).contain("\"featured\":[\"name\",\"nationality\",\"birthDate\"]");
    }

    @Test
    public void test_the_version_it_bundles_is_served() {
        get("/api/models/ftm?version=" + VERSION).should().respond(200)
                                                 .contain("\"version\":\"%s\"".formatted(VERSION));
    }

    @Test
    public void test_a_version_it_does_not_bundle_is_not_found() {
        get("/api/models/ftm?version=3.5.9").should().respond(404).contain("3.5.9").contain(VERSION);
    }

    @Test
    public void test_an_unknown_model_is_not_found_and_the_body_names_the_known_ones() {
        get("/api/models/wikidata").should().respond(404).contain("wikidata").contain("ftm");
    }

    @Test
    public void test_a_client_that_already_holds_the_ontology_gets_a_304() {
        String etag = get("/api/models/ftm").response().header("ETag").get(0);

        get("/api/models/ftm").withHeader("If-None-Match", etag).should().respond(304);
    }
}
