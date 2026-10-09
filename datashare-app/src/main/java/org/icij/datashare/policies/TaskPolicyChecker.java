package org.icij.datashare.policies;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.session.DatashareUser;
import java.io.Serializable;

/**
 * Answers a {@link TaskPolicy} outside a route, for callers that reach a resource method directly and so skip
 * its around-annotation. Bound per mode like the annotation itself: {@link TaskPolicyAnnotation} where
 * ServerMode registers it, {@link #ALLOW_ALL} where no mode does.
 */
public interface TaskPolicyChecker {
    TaskPolicyChecker ALLOW_ALL = (user, task, policy) -> true;

    boolean isAllowed(DatashareUser user, Task<Serializable> task, TaskPolicy policy);
}
