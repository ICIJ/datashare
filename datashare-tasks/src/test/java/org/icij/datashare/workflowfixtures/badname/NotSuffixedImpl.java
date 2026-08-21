package org.icij.datashare.workflowfixtures.badname;

import java.util.Map;

public class NotSuffixedImpl implements NotSuffixed {
    @Override
    public String run(Map<String, Object> args) {
        return "not suffixed";
    }
}
