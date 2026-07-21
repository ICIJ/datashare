package org.icij.datashare.web.testhelpers;

import net.codestory.http.Cookies;
import net.codestory.http.Part;
import net.codestory.http.Query;
import net.codestory.http.Request;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MockRequest implements Request {
    private final MockQueryImpl query;
    public MockRequest(Map<String, String> query) {
        this.query = new MockQueryImpl(query);
    }

    @Override
    public Query query() {
        return this.query;
    }

    @Override
    public String uri() {return null;}

    @Override
    public String method() { return null;}

    @Override
    public String content() throws IOException { return null;}

    @Override
    public String contentType() { return null;}

    @Override
    public InputStream inputStream() { return null;}

    @Override
    public List<String> headerNames() { return null;}

    @Override
    public List<String> headers(String name) { return null;}

    @Override
    public String header(String name) { return null; }

    @Override
    public InetSocketAddress clientAddress() { return null; }

    @Override
    public boolean isSecure() {return true;}

    @Override
    public Cookies cookies() { return null; }


    @Override
    public List<Part> parts() { return null; }

    @Override
    public <T> T unwrap(Class<T> type) { return null; }
    static class MockQueryImpl implements Query {
        Map<String, String> query;

        MockQueryImpl(Map<String, String> query) {
            this.query = query;
        }

        @Override
        public Collection<String> keys() {
            return query.keySet();
        }

        @Override
        public Iterable<String> all(String s) {
            return Optional.ofNullable(this.query.get(s)).stream().toList();
        }

        @Override
        public <T> T unwrap(Class<T> aClass) {
            return null;
        }
    }
}

