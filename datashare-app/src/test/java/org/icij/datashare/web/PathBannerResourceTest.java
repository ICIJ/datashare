package org.icij.datashare.web;

import org.icij.datashare.PathBanner;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Paths;

import static java.util.Collections.singletonList;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.when;

public class PathBannerResourceTest extends AbstractProdWebServerTest {
    @Mock JooqRepository jooqRepository;

    @Test
    public void test_forbidden_get_for_path() {
        get("/api/project/pathBanners/url").should().respond(403);
    }

    @Test
    public void test_forbidden_put_path_banner() {
        put("/api/project/pathBanners/some/path", "{}").should().respond(403);
    }

    @Test
    public void test_forbidden_delete_path_banner() {
        delete("/api/project/pathBanners/some/path").should().respond(403);
    }

    @Test
    public void test_get_notes_for_path() {
        PathBanner pathBanner = new PathBanner(project("local-datashare"), Paths.get("/path/to/note"), "this is a note");
        when(jooqRepository.getPathBanners(project("local-datashare"), "url")).thenReturn(singletonList(pathBanner));
        get("/api/local-datashare/pathBanners/url").should().respond(200).
                contain("\"path\":\"/path/to/note\"").
                contain("\"variant\":\"info\"").
                contain("\"blurSensitiveMedia\":false").
                contain("\"note\":\"this is a note\"");
    }

    @Test
    public void test_forbidden_for_project() {
        get("/api/project/pathBanners").should().respond(403);
    }

    @Test
    public void test_get_path_banners_for_project() {
        PathBanner pathBanner = new PathBanner(project("local-datashare"), Paths.get("/path/to/note"), "this is a note");
        when(jooqRepository.getProjectPathBanners(project("local-datashare"))).thenReturn(singletonList(pathBanner));
        get("/api/local-datashare/pathBanners").should().respond(200).
                contain("\"path\":\"/path/to/note\"").
                contain("\"variant\":\"info\"").
                contain("\"blurSensitiveMedia\":false").
                contain("\"note\":\"this is a note\"");
    }

    @Test
    public void test_get_path_banners_for_project_with_blurred_sensitive_media() {
        PathBanner pathBanner = new PathBanner(project("local-datashare"), Paths.get("/path/to/note"), "this is a note", PathBanner.Variant.danger, true);
        when(jooqRepository.getProjectPathBanners(project("local-datashare"))).thenReturn(singletonList(pathBanner));
        get("/api/local-datashare/pathBanners").should().respond(200).
                contain("\"path\":\"/path/to/note\"").
                contain("\"variant\":\"danger\"").
                contain("\"blurSensitiveMedia\":true").
                contain("\"note\":\"this is a note\"");
    }


    @Test
    public void test_save_path_banner() {
        PathBanner pathBanner = new PathBanner(project("local-datashare"), Paths.get("/path/to/note"), "this is a note");
        //create
        when(jooqRepository.save(pathBanner)).thenReturn(true);
        put("/api/local-datashare/pathBanners/path/to/note",
                "{\"project\":{\"id\":\"local-datashare\"},\"note\":\"this is a note\",\"variant\":\"info\",\"blurSensitiveMedia\":false}")
                .should().respond(201);
        //update
        when(jooqRepository.save(pathBanner)).thenReturn(false);
        put("/api/local-datashare/pathBanners/path/to/note",
                "{\"project\":{\"id\":\"local-datashare\"},\"note\":\"this is a note\",\"variant\":\"info\",\"blurSensitiveMedia\":false}")
                .should().respond(200);
    }


    @Test
    public void test_delete_path_banner() {
        when(jooqRepository.deletePathBanner(project("local-datashare"), "path/to/note")).thenReturn(true);
        delete("/api/local-datashare/pathBanners/path/to/note").should().respond(204);
    }

    @Test
    public void test_delete_greedy_path_banner() {
        when(jooqRepository.deleteGreedyPathBanner(project("local-datashare"), "path/to/note")).thenReturn(true);
        delete("/api/local-datashare/pathBanners/path/to/note?greedy=true").should().respond(204);
    }

    @Test
    public void test_delete_project_path_banners() {
        when(jooqRepository.deleteProjectPathBanners(project("local-datashare"))).thenReturn(true);
        delete("/api/local-datashare/pathBanners/path/to/note").should().respond(204);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        configure(routes -> routes.add(new PathBannerResource(jooqRepository)).
                filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
