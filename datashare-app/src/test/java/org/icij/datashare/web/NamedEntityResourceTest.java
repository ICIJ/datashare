package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.NamedEntity.create;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

public class NamedEntityResourceTest extends AbstractProdWebServerTest {
    @Mock Indexer indexer;
    @Mock JooqRepository jooqRepository;

    @Test
    public void test_get_standalone_named_entity_should_return_not_found() {
        get("/api/index/namedEntity/my_id").should().respond(404);
    }

    @Test
    public void test_get_named_entity() {
        NamedEntity toBeReturned = create(PERSON, "mention", singletonList(123L), "docId", "root", CORENLP, FRENCH);
        doReturn(toBeReturned).when(indexer).get("index", "my_id", "root_parent");
        get("/api/index/namedEntities/my_id?routing=root_parent").should().respond(200).haveType("application/json");
    }

    @Test
    public void test_get_named_entity_in_prod_mode() {
        configure(routes -> routes.add(new NamedEntityResource(indexer)).filter(new BasicAuthFilter("/", "icij", DatashareUser.singleUser("anne"))));
        NamedEntity toBeReturned = create(PERSON, "mention", singletonList(123L), "docId", "root", CORENLP, FRENCH);
        doReturn(toBeReturned).when(indexer).get("anne-datashare", "my_id", "root_parent");

        get("/api/anne-datashare/namedEntities/my_id?routing=root_parent").withAuthentication("anne", "notused").
                should().respond(200).haveType("application/json");
    }

    @Test
    public void test_hide_named_entity_when_success() throws IOException {
        NamedEntity toBeHidden = create(PERSON, "to_update", singletonList(123L), "docId", "root", CORENLP, FRENCH);
        assertThat(toBeHidden.isHidden()).isFalse();
        Indexer.QueryBuilderSearcher searcher = mock(Indexer.QueryBuilderSearcher.class);
        doReturn(Stream.of(toBeHidden)).when(searcher).execute();
        doReturn(searcher).when(searcher).thatMatchesFieldValue(any(), any());
        doReturn(searcher).when(indexer).search(singletonList("index"), NamedEntity.class);

        put("/api/index/namedEntities/hide/to_update").should().respond(200);

        verify(indexer).bulkUpdate("index", singletonList(toBeHidden));
    }

    @Test
    public void test_hide_named_entity_when_failure() {
        doThrow(new RuntimeException()).when(indexer).search(singletonList("index"), NamedEntity.class);

        put("/api/index/namedEntities/hide/to_update").should().respond(500);
    }

    @Before
    public void setUp() {
        openMocks(this);
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        LocalUserFilter localUserFilter = new LocalUserFilter(propertiesProvider, jooqRepository);
        configure(routes -> routes.add(new NamedEntityResource(indexer)).filter(localUserFilter));
    }
}
