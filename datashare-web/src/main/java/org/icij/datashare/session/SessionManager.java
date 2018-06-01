package org.icij.datashare.session;

import java.util.Map;

public interface SessionManager {
    void createSession(String id, Map<String, String> sessionMap);
    Map<String, String> getSession(String id);
}
