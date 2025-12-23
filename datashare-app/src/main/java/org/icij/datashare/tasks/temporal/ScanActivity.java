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
public interface ScanActivity {
    class ScanPayload extends TemporalPayloadImpl {
        private final String dataDir;

        @JsonCreator
        ScanPayload(@JsonProperty("user") User user, @JsonProperty("dataDir") String dataDir) {
            super(user);
            this.dataDir = dataDir;
        }

        @Override
        public Map<String, Object> toDatashareArgs() {
            return Stream.concat(
                super.toDatashareArgs().entrySet().stream(),
                Map.of("dataDir", dataDir).entrySet().stream()
            ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    @ActivityMethod(name = "scan")
    long scan(ScanPayload payload) throws Exception;
}
