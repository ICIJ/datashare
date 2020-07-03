package org.icij.datashare.user;

import org.icij.datashare.Entity;

public interface ApiKey extends Entity {
    boolean match(String base64Key);
    User getUser();
}
