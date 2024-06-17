package org.icij.datashare.test;

import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import static org.fest.assertions.Assertions.assertThat;


public class LogbackCapturingRuleTest {
    Logger logger = LoggerFactory.getLogger(getClass());
    @Rule public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

    @Test
    public void test_log() {
        logger.debug("test log debug");
        logger.info("test log info");
        logger.warn("test log warn");
        logger.error("test log error");

        assertThat(logbackCapturingRule.logs(Level.DEBUG)).contains("test log debug");
        assertThat(logbackCapturingRule.logs(Level.INFO)).contains("test log info");
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains("test log warn");
        assertThat(logbackCapturingRule.logs(Level.ERROR)).contains("test log error");
    }
}