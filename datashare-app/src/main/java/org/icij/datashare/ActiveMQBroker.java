package org.icij.datashare;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;

public class ActiveMQBroker implements Runnable {
    protected final EmbeddedActiveMQ server;

    public ActiveMQBroker() throws Exception {
        Configuration config = new ConfigurationImpl();
        // TODO: should we put back this one
        config.addAcceptorConfiguration("in-vm", "vm://0");
        config.addAcceptorConfiguration("tcp", "tcp://localhost:61616");
        config.addAcceptorConfiguration("amqp", "tcp://localhost:5672?protocols=AMQP");
        config.addAcceptorConfiguration("stomp", "tcp://localhost:61613?protocols=STOMP");
        config.setWildcardRoutingEnabled(true);
        config.addAddressSetting("#", new AddressSettings().setDefaultAddressRoutingType(
            RoutingType.ANYCAST));
        config.setPersistenceEnabled(false);
        // TODO: fix this one
        config.setSecurityEnabled(false);
        server = new EmbeddedActiveMQ();
        server.setConfiguration(config);
    }

    @Override
    public void run() {
        try {
            this.server.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
