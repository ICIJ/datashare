package org.icij.datashare.web;

import org.icij.datashare.DocumentUserRecommendation;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class DocumentUserRecommendationResourceTest extends AbstractProdWebServerTest {
    @Mock
    PropertiesProvider propertiesProvider;
    @Mock
    JooqRepository jooqRepository;
    final Project projectFoo = new Project("foo");
    final Project projectBar = new Project("bar");
    final User janeDoe = new User("jdoe", "Jane Doe", "jdoe@icij.org");

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> {
            propertiesProvider = new PropertiesProvider(Map.of("mode", "LOCAL"));
            DocumentUserRecommendationResource resource = new DocumentUserRecommendationResource(jooqRepository);
            routes.filter(new LocalUserFilter(propertiesProvider, jooqRepository)).add(resource);
        });
    }

    @Test
    public void test_get_document_user_recommendations() {
        Document document = DocumentBuilder.createDoc("bar-0").build();
        DocumentUserRecommendation recommendation = new DocumentUserRecommendation(document, projectBar, janeDoe);
        List<Project> projects = List.of(projectBar, projectFoo);
        when(jooqRepository.getProjects(any())).thenReturn(projects);
        when(jooqRepository.getDocumentUserRecommendations(0, 50, projects)).thenReturn(List.of(recommendation));

        get("/api/document-user-recommendation/").should()
                .respond(200)
                // Get nested project name
                .contain("\"name\":\"bar\"")
                // Get nested user email
                .contain("\"email\":\"jdoe@icij.org\"")
                // Get nested document id
                .contain("\"id\":\"bar-0\"");
    }

    @Test
    public void test_get_document_user_recommendations_for_all_projects() {
        Document bar0 = DocumentBuilder.createDoc("bar-0").build();
        Document foo0 = DocumentBuilder.createDoc("foo-0").build();
        DocumentUserRecommendation barRecommendation = new DocumentUserRecommendation(bar0, projectBar, janeDoe);
        DocumentUserRecommendation fooRecommendation = new DocumentUserRecommendation(foo0, projectFoo, janeDoe);
        List<Project> projects = List.of(projectBar, projectFoo);
        when(jooqRepository.getProjects(any())).thenReturn(projects);
        when(jooqRepository.getDocumentUserRecommendations(0, 50, projects)).thenReturn(List.of(barRecommendation, fooRecommendation));

        get("/api/document-user-recommendation/").should()
                // Get nested document ids
                .contain("\"id\":\"bar-0\"")
                .contain("\"id\":\"foo-0\"");
    }

    @Test
    public void test_get_document_user_recommendations_with_size() {
        Document document = DocumentBuilder.createDoc("bar-0").build();
        DocumentUserRecommendation recommendation = new DocumentUserRecommendation(document, projectBar, janeDoe);
        List<Project> projects = List.of(projectBar, projectFoo);
        when(jooqRepository.getProjects(any())).thenReturn(projects);
        when(jooqRepository.getDocumentUserRecommendations(0, 1, projects)).thenReturn(List.of(recommendation));

        get("/api/document-user-recommendation/?size=1").should()
                .respond(200)
                // Get nested project name
                .contain("\"name\":\"bar\"")
                // Get nested user email
                .contain("\"email\":\"jdoe@icij.org\"")
                // Get nested document id
                .contain("\"id\":\"bar-0\"");
    }

    @Test
    public void test_get_document_user_recommendations_with_correct_project() {
        Document document = DocumentBuilder.createDoc("bar-0").build();
        DocumentUserRecommendation recommendation = new DocumentUserRecommendation(document, projectBar, janeDoe);
        List<Project> projects = List.of(projectBar, projectFoo);
        when(jooqRepository.getProjects(any())).thenReturn(projects);
        when(jooqRepository.getDocumentUserRecommendations(0, 50, List.of(projectBar))).thenReturn(List.of(recommendation));

        get("/api/document-user-recommendation/?project=bar").should().respond(200).contain("\"name\":\"bar\"");
    }

    @Test
    public void test_get_document_user_recommendations_with_other_project() {
        Document document = DocumentBuilder.createDoc("bar-0").build();
        DocumentUserRecommendation recommendation = new DocumentUserRecommendation(document, projectBar, janeDoe);
        List<Project> projects = List.of(projectBar, projectFoo);
        when(jooqRepository.getProjects(any())).thenReturn(projects);
        when(jooqRepository.getDocumentUserRecommendations(0, 50, List.of(projectFoo))).thenReturn(List.of());
        when(jooqRepository.getDocumentUserRecommendations(0, 50, List.of(projectBar))).thenReturn(List.of(recommendation));
        when(jooqRepository.getDocumentUserRecommendations(0, 50, projects)).thenReturn(List.of(recommendation));

        get("/api/document-user-recommendation/?project=foo").should().respond(200).not().contain("\"name\":\"bar\"");
    }
}
