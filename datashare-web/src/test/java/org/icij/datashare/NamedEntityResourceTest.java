package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.NamedEntity.create;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

public class NamedEntityResourceTest implements FluentRestTest {
    @Mock
    Indexer indexer;
     private static WebServer server = new WebServer() {
         @Override
         protected Env createEnv() {
             return Env.prod();
         }
     }.startOnRandomPort();

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        server.configure(routes -> routes.add(new NamedEntityResource(indexer)));
    }

    @Test
    public void test_get_standalone_named_entity_should_return_not_found() {
        get("/api/namedEntity/my_id").should().respond(404);
    }

    @Test
    public void test_get_child_named_entity() {
        NamedEntity toBeReturned = create(PERSON, "mention", 123, "docId", CORENLP, FRENCH);
        doReturn(toBeReturned).when(indexer).get("local-datashare", "my_id", "root_parent");
        get("/api/namedEntity/my_id?routing=root_parent").should().respond(200).haveType("application/json").contain(toBeReturned.getId());
    }

    @Override
    public int port() {
        return server.port();
    }
}
