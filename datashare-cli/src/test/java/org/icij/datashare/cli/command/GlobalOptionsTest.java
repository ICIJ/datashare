package org.icij.datashare.cli.command;


import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class GlobalOptionsTest extends AbstractDatashareCommandTest{

    @Test
    public void test_elasticsearch_max_idle_connection_time_is_taken_into_account() {
        Properties props = parse(
                "--elasticsearchMaxIdleConnectionTime", "29");
        assertThat(props).includes(entry("elasticsearchMaxIdleConnectionTime", "29"));
    }

}