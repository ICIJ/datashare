package org.icij.datashare.test;

import org.junit.rules.ExternalResource;
import org.slf4j.event.Level;

import java.util.List;

public class LogbackCapturingRule extends ExternalResource {
    LogbackAppenderWrapper logbackWrapper = new LogbackAppenderWrapper();

    @Override
    protected void after() {
        logbackWrapper.reset();
    }

    public List<String> logs(Level level) {
        return logbackWrapper.logs(level);
    }

    public List<String> logs() {
        return logbackWrapper.logs();
    }
}
