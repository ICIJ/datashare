package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(jooqRepository.getProjects()).thenReturn(List.of(project("prj")));
        configure(routes -> routes.add(new EntityExportResource(statements))
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

        get("/api/prj/entities/export").should().respond(200).haveType("application/x-ndjson")
                .haveHeader("Content-Disposition", "attachment;filename=\"prj.ftm.json\"")
                .contain("\"id\":\"p-1\"").contain("\"id\":\"p-2\"");
    }

    @Test
    public void test_an_empty_model_parameter_means_ftm() {
        projectHolds(person("p-1", "Ada"));

        get("/api/prj/entities/export?model=").should().respond(200).contain("\"id\":\"p-1\"");
    }

    @Test
    public void test_a_project_with_no_entities_gives_an_empty_body() {
        projectHolds();

        get("/api/prj/entities/export").should().respond(200).haveType("application/x-ndjson").not().contain("\"id\"");
    }

    @Test
    public void test_a_model_with_no_exporter_is_a_bad_request_and_reads_nothing() {
        get("/api/prj/entities/export?model=wikidata").should().respond(400).contain("unknown data model 'wikidata'");

        verify(statements, never()).entities(anyString(), anyString(), any());
    }

    @Test
    public void test_a_project_the_user_is_not_granted_is_forbidden() {
        get("/api/other/entities/export").should().respond(403);

        verify(statements, never()).entities(anyString(), anyString(), any());
    }
}
