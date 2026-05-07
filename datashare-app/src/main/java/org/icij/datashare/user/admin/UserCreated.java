package org.icij.datashare.user.admin;

import java.util.List;

public record UserCreated(
        String login,
        String email,
        String name,
        String provider,
        List<String> groups,
        boolean noop
) {}
