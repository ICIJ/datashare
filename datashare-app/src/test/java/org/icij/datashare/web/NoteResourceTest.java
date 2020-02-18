package org.icij.datashare.web;

import org.icij.datashare.Note;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Paths;

import static java.util.Collections.singletonList;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class NoteResourceTest extends AbstractProdWebServerTest {
    @Mock Repository repository;

    @Test
    public void test_forbidden_for_path() {
        get("/api/project/notes/url").should().respond(403);
    }

    @Test
    public void test_get_notes_for_path() {
        when(repository.getNotes(project("local-datashare"), "url")).thenReturn(singletonList(new Note(project("local-datashare"), Paths.get("/path/to/note"), "this is a note")));
        get("/api/local-datashare/notes/url").should().respond(200).
                contain("/path/to/note").
                contain("this is a note");
    }

    @Test
    public void test_forbidden_for_project() {
        get("/api/project/notes").should().respond(403);
    }

    @Test
    public void test_get_notes_for_project() {
        when(repository.getNotes(project("local-datashare"))).thenReturn(singletonList(new Note(project("local-datashare"), Paths.get("/path/to/note"), "this is a note")));
        get("/api/local-datashare/notes").should().respond(200).
                contain("/path/to/note").
                contain("this is a note");
    }

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new NoteResource(repository)).
                filter(new LocalUserFilter(new PropertiesProvider())));
    }
}
