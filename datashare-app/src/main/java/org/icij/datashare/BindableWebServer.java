package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.internal.Handler;
import net.codestory.http.internal.HttpServerWrapper;
import net.codestory.http.websockets.WebSocketHandler;
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

    BindableWebServer(String host) {
        this.host = host;
    }

    @Override
    protected HttpServerWrapper createHttpServer(Handler handler, WebSocketHandler webSocketHandler) {
        HttpServerWrapper delegate = super.createHttpServer(handler, webSocketHandler);
        return new HostAwareServerWrapper(delegate, host, threadCount, selectThreads, webSocketThreads);
    }

    private static class HostAwareServerWrapper implements HttpServerWrapper {
        private final Container container;
        private final Service service;
        private final String host;
        private final int threadCount;
        private final int selectThreads;
        private final int webSocketThreads;
        private SocketConnection socketConnection;

        HostAwareServerWrapper(HttpServerWrapper delegate, String host,
                int threadCount, int selectThreads, int webSocketThreads) {
            this.container = (Container) delegate;
            this.service = (Service) delegate;
            this.host = host;
            this.threadCount = threadCount;
            this.selectThreads = selectThreads;
            this.webSocketThreads = webSocketThreads;
        }

        @Override
        public int start(int port, SSLContext sslContext, boolean requiresClientAuth) throws IOException {
            DirectRouter router = new DirectRouter(service);
            RouterContainer routerContainer = new RouterContainer(container, router, webSocketThreads);
            ContainerSocketProcessor processor = new ContainerSocketProcessor(routerContainer, threadCount, selectThreads);
            socketConnection = new SocketConnection(processor);
            InetSocketAddress boundAddress = (InetSocketAddress) socketConnection.connect(
                    new InetSocketAddress(host, port), sslContext);
            return boundAddress.getPort();
        }

        @Override
        public void stop() throws IOException {
            socketConnection.close();
        }
    }
}
