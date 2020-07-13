package org.icij.datashare.web;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.DelApiKeyTask;
import org.icij.datashare.tasks.GenApiKeyTask;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ApiKeyResourceTest extends AbstractProdWebServerTest {
    @Mock public TaskFactory taskFactory;

    @Test
    public void test_generate_key() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("privateKey");
        when(taskFactory.createGenApiKey(any())).thenReturn(task);

        put("/api/key/create").should().respond(201).haveType("application/json").contain("privateKey");
        put("/api/key/create").should().respond(201).haveType("application/json").contain("privateKey");
    }

    @Test
    public void test_delete_key() throws Exception {
        DelApiKeyTask task = mock(DelApiKeyTask.class);
        when(task.call()).thenReturn(true);
        when(taskFactory.deleteApiKey(any())).thenReturn(task);

        delete("/api/key").should().respond(204);
    }

    @Test
    public void test_delete_key_false() throws Exception {
        DelApiKeyTask task = mock(DelApiKeyTask.class);
        when(task.call()).thenReturn(false);
        when(taskFactory.deleteApiKey(any())).thenReturn(task);

        delete("/api/key").should().respond(404);
    }

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new ApiKeyResource(taskFactory)).filter(new LocalUserFilter(new PropertiesProvider())));
    }
}
