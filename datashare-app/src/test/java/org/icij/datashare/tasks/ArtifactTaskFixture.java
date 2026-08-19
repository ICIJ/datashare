package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;

import java.util.Map;

/** Builds the Task an ArtifactTask reads its configuration from. Shared by the three artifact test classes. */
class ArtifactTaskFixture {
    static Task<Long> taskWith(Map<String, Object> args) {
        return new Task<>(ArtifactTask.class.getName(), User.local(), args);
    }
}
