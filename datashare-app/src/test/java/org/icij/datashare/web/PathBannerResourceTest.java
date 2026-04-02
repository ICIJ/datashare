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


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        configure(routes -> routes.add(new PathBannerResource(jooqRepository)).
                filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
