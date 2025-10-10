package org.icij.datashare.web;

import org.icij.datashare.Note;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
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
    @Mock JooqRepository jooqRepository;

    @Test
    public void test_forbidden_for_path() {
        get("/api/project/notes/url").should().respond(403);
    }

    @Test
    public void test_get_notes_for_path() {
        Note note = new Note(project("local-datashare"), Paths.get("/path/to/note"), "this is a note");
        when(jooqRepository.getNotes(project("local-datashare"), "url")).thenReturn(singletonList(note));
        get("/api/local-datashare/notes/url").should().respond(200).
                contain("\"path\":\"/path/to/note\"").
                contain("\"blurSensitiveMedia\":false").
                contain("\"note\":\"this is a note\"");
    }

    @Test
    public void test_forbidden_for_project() {
        get("/api/project/notes").should().respond(403);
    }

    @Test
    public void test_get_notes_for_project() {
        Note note = new Note(project("local-datashare"), Paths.get("/path/to/note"), "this is a note");
        when(jooqRepository.getNotes(project("local-datashare"))).thenReturn(singletonList(note));
        get("/api/local-datashare/notes").should().respond(200).
                contain("\"path\":\"/path/to/note\"").
                contain("\"blurSensitiveMedia\":false").
                contain("\"note\":\"this is a note\"");
    }

    @Test
    public void test_get_notes_for_project_with_blured_sensitive_media() {
        Note note = new Note(project("local-datashare"), Paths.get("/path/to/note"), "this is a note");
        when(jooqRepository.getNotes(project("local-datashare"))).thenReturn(singletonList(note));
        get("/api/local-datashare/notes").should().respond(200).
                contain("\"path\":\"/path/to/note\"").
                contain("\"blurSensitiveMedia\":true").
                contain("\"note\":\"this is a note\"");
    }


    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new NoteResource(jooqRepository)).
                filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
