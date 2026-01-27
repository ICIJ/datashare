package org.icij.datashare.process;

import org.icij.datashare.test.LogbackCapturingRule;
import org.junit.Rule;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class ProcessTest {
    @Rule public LogbackCapturingRule logs = new LogbackCapturingRule();

    @Test
    public void test_launch_and_kill_process() {
        Process process = new Process(System.getProperty("user.dir"), "java-test", new String[]{"java"}, 12345);
        process.start();
        assertThat(process.isAlive()).isTrue();
        process.kill();
    }
}