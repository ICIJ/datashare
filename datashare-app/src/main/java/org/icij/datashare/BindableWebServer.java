package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.internal.Handler;
import net.codestory.http.internal.HttpServerWrapper;
import net.codestory.http.misc.Env;
import net.codestory.http.websockets.WebSocketHandler;
import org.simpleframework.common.buffer.Allocator;
import org.simpleframework.common.buffer.FileAllocator;
import org.simpleframework.http.core.Container;
import org.simpleframework.http.core.ContainerSocketProcessor;
import org.simpleframework.http.socket.service.DirectRouter;
import org.simpleframework.http.socket.service.RouterContainer;
import org.simpleframework.http.socket.service.Service;
import org.simpleframework.transport.connect.SocketConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;

class BindableWebServer extends WebServer {
    private final String host;
    private final long maxBodyBytes;

    BindableWebServer(String host, long maxBodyBytes) {
        this.host = host;
        this.maxBodyBytes = maxBodyBytes;
    }

    // Force production mode regardless of the PROD_MODE system property, so running the fat jar
    // directly cannot leave development mode on (error reasons in headers, the .source route).
    // Env.prod() also turns live reload off, which -DPROD_MODE=true alone does not: fluent-http reads
    // it from http.livereload.server (default true), so the packaged launchers were serving it too.
    @Override
    protected Env createEnv() {
        return Env.prod();
    }

    @Override
    protected HttpServerWrapper createHttpServer(Handler handler, WebSocketHandler webSocketHandler) {
        HttpServerWrapper delegate = super.createHttpServer(handler, webSocketHandler);
        Allocator allocator = requestBodyAllocator();
        return new HostAwareServerWrapper(delegate, host, allocator, threadCount, selectThreads, webSocketThreads);
    }

    private Allocator requestBodyAllocator() {
        Allocator fileAllocator = new FileAllocator();
        if (maxBodyBytes <= 0) {
            return fileAllocator;
        }
        return new MaxBodySizeAllocator(fileAllocator, maxBodyBytes);
    }

    private static class HostAwareServerWrapper implements HttpServerWrapper {
        private final Container container;
        private final Service service;
        private final String host;
        private final Allocator allocator;
        private final int threadCount;
        private final int selectThreads;
        private final int webSocketThreads;
        private SocketConnection socketConnection;

        HostAwareServerWrapper(HttpServerWrapper delegate, String host, Allocator allocator, int threadCount,
                               int selectThreads, int webSocketThreads) {
            this.container = (Container) delegate;
            this.service = (Service) delegate;
            this.host = host;
            this.allocator = allocator;
            this.threadCount = threadCount;
            this.selectThreads = selectThreads;
            this.webSocketThreads = webSocketThreads;
        }

        @Override
        public int start(int port, SSLContext sslContext, boolean requiresClientAuth) throws IOException {
            DirectRouter router = new DirectRouter(service);
            RouterContainer routerContainer = new RouterContainer(container, router, webSocketThreads);
            ContainerSocketProcessor processor =
                    new ContainerSocketProcessor(routerContainer, allocator, threadCount, selectThreads);
            socketConnection = new SocketConnection(processor);
            InetSocketAddress boundAddress =
                    (InetSocketAddress) socketConnection.connect(new InetSocketAddress(host, port), sslContext);
            return boundAddress.getPort();
        }

        @Override
        public void stop() throws IOException {
            socketConnection.close();
        }
    }
}
