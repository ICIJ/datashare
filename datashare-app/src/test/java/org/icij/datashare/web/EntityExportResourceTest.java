package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import net.codestory.http.Context;
import net.codestory.http.payload.StreamingOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class EntityExportResourceTest extends AbstractProdWebServerTest {
    @Mock JooqRepository jooqRepository;
    @Mock StatementRepository statements;
    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = openMocks(this);
        when(jooqRepository.getProjects()).thenReturn(List.of(project("prj"), project("ftm")));
        configure(routes -> routes.add(new EntityExportResource(statements)).add(new FtmResource(mock(Indexer.class)))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    private void projectHolds(ModelEntity... entities) {
        when(statements.entities(eq("prj"), eq("ftm"), any())).thenAnswer(
                call -> call.<Function<Stream<ModelEntity>, Object>>getArgument(2).apply(Stream.of(entities)));
    }

    private static ModelEntity person(String id, String name) {
        return new ModelEntity("ftm", id, "Person", Set.of(), Set.of(), Map.of("name", List.of(name)));
    }

    @Test
    public void test_export_streams_the_project_entities_as_ftm_ndjson() {
        projectHolds(person("p-1", "Ada"), person("p-2", "Grace"));

        get("/api/entities/prj/export").should().respond(200).haveType("application/x-ndjson")
                .haveHeader("Content-Disposition", "attachment;filename=\"prj.ftm.json\"")
                .contain("\"id\":\"p-1\"").contain("\"id\":\"p-2\"");
    }

    @Test
    public void test_a_project_named_ftm_is_not_shadowed_by_the_ftm_document_route() {
        when(statements.entities(eq("ftm"), eq("ftm"), any())).thenAnswer(
                call -> call.<Function<Stream<ModelEntity>, Object>>getArgument(2)
                            .apply(Stream.of(person("p-1", "Ada"))));

        get("/api/entities/ftm/export").should().respond(200).haveType("application/x-ndjson")
                .contain("\"id\":\"p-1\"");
    }

    @Test
    public void test_an_empty_model_parameter_means_ftm() {
        projectHolds(person("p-1", "Ada"));

        get("/api/entities/prj/export?model=").should().respond(200).contain("\"id\":\"p-1\"");
    }

    @Test
    public void test_a_project_with_no_entities_gives_an_empty_body() {
        projectHolds();

        get("/api/entities/prj/export").should().respond(200).haveType("application/x-ndjson").not().contain("\"id\"");
    }

    @Test
    public void test_a_model_with_no_exporter_is_a_bad_request_and_reads_nothing() {
        get("/api/entities/prj/export?model=wikidata").should().respond(400).contain("unknown data model 'wikidata'");

        verify(statements, never()).entities(anyString(), anyString(), any());
    }

    @Test
    public void test_a_project_the_user_is_not_granted_is_forbidden() {
        get("/api/entities/other/export").should().respond(403);

        verify(statements, never()).entities(anyString(), anyString(), any());
    }

    private void projectFailsAfter(int written) {
        Stream<ModelEntity> entities = Stream.concat(
                IntStream.range(0, written).mapToObj(i -> person("p-" + i, "Ada")),
                Stream.of(new ModelEntity("ftm", "w-1", "Wizard", Set.of(), Set.of(), Map.of())));
        when(statements.entities(eq("prj"), eq("ftm"), any())).thenAnswer(
                call -> call.<Function<Stream<ModelEntity>, Object>>getArgument(2).apply(entities));
    }

    @Test
    public void test_a_failure_before_anything_is_sent_is_a_server_error() {
        projectFailsAfter(1);

        get("/api/entities/prj/export").should().respond(500);
    }

    @Test
    public void test_a_failure_after_part_of_the_export_is_sent_keeps_the_200_and_cuts_the_body() {
        projectFailsAfter(10_000);

        get("/api/entities/prj/export").should().respond(200).contain("\"id\":\"p-0\"")
                .not().contain("\"id\":\"p-9999\"").contain("<html");
    }

    @Test
    public void test_a_failed_write_reaches_fluent_http_as_an_io_exception() {
        projectHolds(person("p-1", "Ada"));
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(new DatashareUser(User.localUser("cecile", "prj")));
        StreamingOutput body = (StreamingOutput) new EntityExportResource(statements).export("prj", null, context)
                .rawContent();

        IOException e = assertThrows(IOException.class, () -> body.write(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
        }));
        assertThat(e.getMessage()).isEqualTo("Broken pipe");
    }
}
