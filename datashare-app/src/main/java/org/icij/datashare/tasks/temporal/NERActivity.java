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
public interface NERActivity {

    class NERPayload extends TemporalPayloadImpl {
        private final String defaultProject;
        private final String nlpPipeline;
        private final String maxContentLength;
        private final String pollingInterval;

        @JsonCreator
        NERPayload(
            @JsonProperty("user") User user,
            @JsonProperty("defaultProject") String defaultProject,
            @JsonProperty("nlpPipeline") String nlpPipeline,
            @JsonProperty("maxContentLength") String maxContentLength,
            @JsonProperty("pollingInterval") String pollingInterval
        ) {
            super(user);
            this.defaultProject = defaultProject;
            this.nlpPipeline = nlpPipeline;
            this.maxContentLength = maxContentLength;
            this.pollingInterval = pollingInterval;
        }

        @Override
        public Map<String, Object> toDatashareArgs() {
            Map<String, Object> nerArgs = Map.of(
                "defaultProject", defaultProject,
                "nlpPipeline", nlpPipeline,
                "maxContentLength", maxContentLength,
                "pollingInterval", pollingInterval
            );
            return Stream.concat(
                super.toDatashareArgs().entrySet().stream(),
                nerArgs.entrySet().stream()
            ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    @ActivityMethod(name = "ner")
    long ner(NERActivity.NERPayload payload) throws Exception;
}
