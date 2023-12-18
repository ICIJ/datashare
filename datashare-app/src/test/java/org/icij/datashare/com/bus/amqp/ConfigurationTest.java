package org.icij.datashare.com.bus.amqp;

import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;

import static org.fest.assertions.Assertions.assertThat;

public class ConfigurationTest {
    @Test(expected = AssertionError.class)
    public void configuration_constructor_url_not_amqp() throws URISyntaxException {
        new Configuration(new URI("http://localhost:12345"));
    }

    @Test
    public void configuration_constructor_url() throws URISyntaxException {
        Configuration configuration = new Configuration(new URI("amqp://localhost:12345"));
        assertThat(configuration.host).isEqualTo("localhost");
        assertThat(configuration.port).isEqualTo(12345);
        assertThat(configuration.user).isEmpty();
        assertThat(configuration.password).isEmpty();
    }

    @Test
    public void configuration_constructor_url_with_user() throws URISyntaxException {
        Configuration configuration = new Configuration(new URI("amqp://user:pass@localhost:12345"));
        assertThat(configuration.user).isEqualTo("user");
        assertThat(configuration.password).isEqualTo("pass");
    }

    @Test
    public void configuration_constructor_url_with_params() throws URISyntaxException {
        Configuration configuration = new Configuration(new URI("amqp://user:pass@localhost:12345?" +
                "deadLetter=true&nbMaxMessages=10&requeueDelay=123&recoveryDelay=321"));
        assertThat(configuration.deadLetter).isTrue();
        assertThat(configuration.nbMaxMessages).isEqualTo(10);
        assertThat(configuration.requeueDelay).isEqualTo(123);
        assertThat(configuration.connectionRecoveryDelay).isEqualTo(321);
    }
}
