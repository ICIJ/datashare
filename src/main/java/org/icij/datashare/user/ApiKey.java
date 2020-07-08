package org.icij.datashare.user;

import org.icij.datashare.Entity;

import java.util.Date;

public interface ApiKey extends Entity {
    boolean match(String base64Key);
    User getUser();
    Date getCreationDate();
}
