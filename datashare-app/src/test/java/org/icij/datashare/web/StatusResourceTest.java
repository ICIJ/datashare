package org.icij.datashare.web;

import com.google.inject.ProvisionException;
import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.DocumentCollectionFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Properties;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.HashMapUser.local;
import static org.icij.datashare.session.HashMapUser.singleUser;


import static org.icij.datashare.text.Project.project;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class StatusResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Mock Repository repository;
    @Mock DataBus dataBus;
    @Mock DocumentCollectionFactory documentCollectionFactory;
    @Mock Indexer indexer;

    @Before
    public void setUp() {
        initMocks(this);
        when(documentCollectionFactory.createQueue(any(),eq("health:queue"))).thenReturn(mock(DocumentQueue.class));
        configure(routes -> routes.add(new StatusResource(new PropertiesProvider(),repository,indexer,dataBus,documentCollectionFactory)));
    }

    @Test
    public void test_get_database_status() {
        when(repository.getHealth()).thenReturn(true);
        get("/api/status").should().respond(200).contain("\"database\":true");
    }

    @Test
    public void test_get_index_status() {
        when(indexer.getHealth()).thenReturn(true);
        get("/api/status").should().respond(200).contain("\"index\":true");
    }

    @Test
    public void test_get_dataBus_status() {
        when(dataBus.getHealth()).thenReturn(true);
        get("/api/status").should().respond(200).contain("\"databus\":true");
    }

    @Test
    public void test_get_queue_status() {
        get("/api/status").should().respond(200).contain("\"queue\":true");
    }

    @Test
    public void test_get_queue_with_guice_exception() {
        when(documentCollectionFactory.createQueue(any(),eq("health:queue"))).thenThrow(new ProvisionException("test"));
        get("/api/status").should().respond(200).contain("\"queue\":false");
    }

    @Test
    public void test_get_queue_with_io_exception() {
        DocumentQueue mockQueue = mock(DocumentQueue.class);
        when(mockQueue.size()).thenThrow(new RuntimeException("test"));
        when(documentCollectionFactory.createQueue(any(),eq("health:queue"))).thenReturn(mockQueue);
        get("/api/status").should().respond(200).contain("\"queue\":false");
    }
}