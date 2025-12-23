package org.icij.datashare.tasks.temporal;

import java.util.Map;
import org.icij.datashare.user.User;

abstract class TemporalPayloadImpl implements TemporalPayload {
    protected final User user;

    public TemporalPayloadImpl(User user) {
        this.user = user;
    }

    @Override
    public Map<String, Object> toDatashareArgs() {
        return Map.of("user", user);
    }

    @Override
    public User getUser() {
        return user;
    }
}