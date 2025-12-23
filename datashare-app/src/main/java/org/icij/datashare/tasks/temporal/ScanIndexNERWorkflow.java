package org.icij.datashare.tasks.temporal;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.icij.datashare.user.User;

@WorkflowInterface
public interface ScanIndexNERWorkflow {
    class ScanIndexNERPayload extends TemporalPayloadImpl {
        private final String dataDir;
        private final String defaultProject;
        private final String nlpPipeline;
        private final String maxContentLength;
        private final String pollingInterval;

        @JsonCreator
        public ScanIndexNERPayload(
            @JsonProperty("user") User user,
            @JsonProperty("dataDir") String dataDir,
            @JsonProperty("defaultProject") String defaultProject,
            @JsonProperty("nlpPipeline") String nlpPipeline,
            @JsonProperty("maxContentLength") String maxContentLength,
            @JsonProperty("pollingInterval") String pollingInterval
        ) {
            super(user);
            this.dataDir = dataDir;
            this.defaultProject = defaultProject;
            this.nlpPipeline = nlpPipeline;
            this.maxContentLength = maxContentLength;
            this.pollingInterval = pollingInterval;
        }

        ScanActivity.ScanPayload asScanPayload() {
            return new ScanActivity.ScanPayload(user, dataDir);
        }

        IndexActivity.IndexPayload asIndexPayload() {
            return new IndexActivity.IndexPayload(user, defaultProject);
        }

        NERActivity.NERPayload asNERPayload() {
            return new NERActivity.NERPayload(user, defaultProject, nlpPipeline, maxContentLength, pollingInterval);
        }
    }

    @WorkflowMethod(name = "scan_index_nlp")
    void scanIndexNER(ScanIndexNERPayload payload) throws Exception;
}
