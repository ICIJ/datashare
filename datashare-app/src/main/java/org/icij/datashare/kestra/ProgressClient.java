package org.icij.datashare.kestra;

import static java.lang.Double.doubleToLongBits;
import static java.lang.Double.longBitsToDouble;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.kestra.sdk.KestraClient;
import io.kestra.sdk.api.ExecutionsApi;
import io.kestra.sdk.api.KvApi;
import io.kestra.sdk.internal.ApiException;
import io.kestra.sdk.model.Execution;
import io.kestra.sdk.model.TaskRun;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.icij.datashare.asynctasks.Progress;
import org.icij.datashare.asynctasks.WeightedProgress;
import org.icij.datashare.json.JsonObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Singleton
public class ProgressClient {
    private final ExecutionsApi executionsApi;
    private final KvApi kvApi;


    @Inject
    public ProgressClient(KestraClient client) {
        executionsApi = client.executions();
        kvApi = client.kv();
    }

    public Flux<Progress> getProgress(String executionId, String tenant, String namespace) {
        AtomicLong maxProgress = new AtomicLong(0);
        AtomicLong progress = new AtomicLong(0);
        return executionsApi.followDependenciesExecution(executionId, tenant, false, true).to
//            .subscribeOn(Schedulers.parallel()) // TODO: put back
            .flatMap(execution -> getAllTaskRuns(execution, tenant, namespace, maxProgress, progress))
            .then(Mono.fromCallable(() ->
                new Progress(longBitsToDouble(progress.get()), longBitsToDouble(maxProgress.get()))
            ));
    }

    private Flux<Void> getAllTaskRuns(Execution execution, String tenant, String namespace,
                                      AtomicLong maxProgress, AtomicLong progress) {
        List<TaskRun> taskRuns = execution.getId() == null ? List.of() : executionsApi.execution(execution.getId(), tenant).getTaskRunList();
        return Flux.fromIterable(Objects.requireNonNull(taskRuns))
            .flatMap(run ->
                Mono.fromCallable(() -> kvApi.keyValue(namespace, "progress-" + run.getId(), tenant).getValue())
                    .onErrorResume(ApiException.class, e -> {
                        if (e.getCode() == 404) {
                            maxProgress.getAndAdd(1);
                            return Mono.empty();
                        }
                        return Mono.error(e);
                    })
                    .flatMap(value -> Mono.fromCallable(() -> {
                        String json = String.valueOf(value);
                        WeightedProgress weightedProgress = JsonObjectMapper.readValue(json, WeightedProgress.class);
                        progress.getAndAdd(
                            doubleToLongBits(weightedProgress.weight() * weightedProgress.progress().progress()));
                        maxProgress.getAndAdd(
                            doubleToLongBits(weightedProgress.weight() * weightedProgress.progress().maxProgress()));
                        return value;
                    }).then())
            );
    }
}


