package org.icij.datashare.tasks.temporal;

import java.util.Map;
import org.icij.datashare.user.User;

interface TemporalPayload {
    Map<String, Object> toDatashareArgs();
    User getUser();
}