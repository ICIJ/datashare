package org.icij.datashare.kestra;

import static java.lang.Double.doubleToLongBits;
import static java.lang.Double.longBitsToDouble;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.kestra.sdk.api.ExecutionsApi;
import io.kestra.sdk.api.KvApi;
import io.kestra.sdk.internal.ApiClient;
import io.kestra.sdk.internal.ApiException;
import io.kestra.sdk.model.Execution;
import io.kestra.sdk.model.TaskRun;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.icij.datashare.asynctasks.Progress;
import org.icij.datashare.asynctasks.WeightedProgress;
import org.icij.datashare.json.JsonObjectMapper;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Singleton
public class ProgressClient {
    private final ExecutionsApi executionsApi;
    private final KvApi kvApi;


    @Inject
    public ProgressClient(ApiClient client) {
        executionsApi = new ExecutionsApi(client);
        kvApi = new KvApi(client);
    }

    public Progress getProgress(String executionId, String tenant, String namespace) throws IOException {
        AtomicLong maxProgress = new AtomicLong(0);
        AtomicLong progress = new AtomicLong(0);
        getAllTaskRuns(executionId, tenant)
            .subscribeOn(Schedulers.parallel())
            .subscribe(rethrowConsumer(taskId -> {
                Object value;
                try {
                    value = kvApi.keyValue(namespace, taskId, tenant).getValue();
                } catch (ApiException e) {
                    if (e.getCode() == 404) { // No progress published yet
                        // We increment the max progress by 1
                        maxProgress.getAndAdd(1);
                        return;
                    }
                    throw e;
                }
                String json = String.valueOf(value);
                WeightedProgress weightedProgress = JsonObjectMapper.readValue(json, WeightedProgress.class);
                progress.getAndAdd(
                    doubleToLongBits(weightedProgress.weight() * weightedProgress.progress().progress()));
                maxProgress.getAndAdd(
                    doubleToLongBits(weightedProgress.weight() * weightedProgress.progress().maxProgress()));
            }));
        return new Progress(longBitsToDouble(progress.get()), longBitsToDouble(maxProgress.get()));
    }

    private Flux<String> getAllTaskRuns(String executionId, String tenant) throws ApiException {
        Function<Execution, Publisher<String>> mapper = execution -> Flux.just(
            Objects.requireNonNull(execution.getTaskRunList()).stream().map(TaskRun::getId).toArray(String[]::new));

        return executionsApi
            .followDependenciesExecution(executionId, tenant, true, true)
            .flatMap(mapper);
    }
}
