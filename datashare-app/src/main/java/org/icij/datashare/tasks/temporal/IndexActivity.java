package org.icij.datashare.tasks.temporal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.icij.datashare.user.User;

@ActivityInterface
public interface IndexActivity {
    class IndexPayload extends TemporalPayloadImpl {
        private final String defaultProject;

        @JsonCreator
        IndexPayload(@JsonProperty("user") User user, @JsonProperty("defaultProject") String defaultProject) {
            super(user);
            this.defaultProject = defaultProject;
        }

        @Override
        public Map<String, Object> toDatashareArgs() {
            return Stream.concat(
                super.toDatashareArgs().entrySet().stream(),
                Map.of("defaultProject", defaultProject).entrySet().stream()
            ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    @ActivityMethod(name = "index")
    long index(IndexPayload payload) throws Exception;
}
