package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.Note;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.LocalUserFilter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Paths;

import static java.util.Collections.singletonList;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class NoteResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
            @Override
            protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    @Mock Repository repository;

    @Test
    public void test_forbidden() {
        get("/api/project/notes/url").should().respond(403);
    }

    @Test
    public void test_get_note() {
        when(repository.getNotes(project("local-datashare"), "url")).thenReturn(singletonList(new Note(project("local-datashare"), Paths.get("/path/to/note"), "this is a note")));
        get("/api/local-datashare/notes/url").should().respond(200).
                contain("/path/to/note").
                contain("this is a note");
    }

    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new NoteResource(repository)).
                filter(new LocalUserFilter(new PropertiesProvider())));
    }

    @Override
    public int port() { return server.port();}
}
