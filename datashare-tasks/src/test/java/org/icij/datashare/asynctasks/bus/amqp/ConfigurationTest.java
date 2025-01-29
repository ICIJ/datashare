package org.icij.datashare.asynctasks.bus.amqp;

import org.fest.assertions.Assertions;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;

public class ConfigurationTest {
    @Test(expected = AssertionError.class)
    public void configuration_constructor_url_not_amqp() throws URISyntaxException {
        new Configuration(new URI("http://localhost:12345"));
    }

    @Test
    public void configuration_constructor_url() throws URISyntaxException {
        Configuration configuration = new Configuration(new URI("amqp://localhost:12345"));
        Assertions.assertThat(configuration.host).isEqualTo("localhost");
        Assertions.assertThat(configuration.port).isEqualTo(12345);
        Assertions.assertThat(configuration.user).isEmpty();
        Assertions.assertThat(configuration.password).isEmpty();
    }

    @Test
    public void configuration_constructor_url_with_user() throws URISyntaxException {
        Configuration configuration = new Configuration(new URI("amqp://user:pass@localhost:12345"));
        Assertions.assertThat(configuration.user).isEqualTo("user");
        Assertions.assertThat(configuration.password).isEqualTo("pass");
    }

    @Test
    public void configuration_constructor_url_with_params() throws URISyntaxException {
        Configuration configuration = new Configuration(new URI("amqp://user:pass@localhost:12345?" +
                "deadLetter=true&nbMaxMessages=10&requeueDelay=123&recoveryDelay=321&monitoring=true"));
        Assertions.assertThat(configuration.rabbitMq).isTrue();
        Assertions.assertThat(configuration.monitoring).isTrue();
        Assertions.assertThat(configuration.nbMaxMessages).isEqualTo(10);
        Assertions.assertThat(configuration.requeueDelay).isEqualTo(123);
        Assertions.assertThat(configuration.connectionRecoveryDelay).isEqualTo(321);
    }
}
