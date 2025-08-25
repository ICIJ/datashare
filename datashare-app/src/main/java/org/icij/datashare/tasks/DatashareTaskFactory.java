package org.icij.datashare.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;


public interface DatashareTaskFactory extends org.icij.datashare.asynctasks.TaskFactory {
    // TODO: ideally we'd like to decorate task functions here using the @ConductorTask
    //  but during inject Guice creates proxy classes which loose the below annotations, instead we has to put
    //  @ConductorTask annotations in each task class, see if this can be improved
    BatchSearchRunner createBatchSearchRunner(Task<?> taskView, Function<Double, Void> updateCallback);

    BatchDownloadRunner createBatchDownloadRunner(Task<?> taskView, Function<Double, Void> updateCallback);

    ScanTask createScanTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    IndexTask createIndexTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    ScanIndexTask createScanIndexTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    ExtractNlpTask createExtractNlpTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    EnqueueFromIndexTask createEnqueueFromIndexTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    DeduplicateTask createDeduplicateTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    ArtifactTask createArtifactTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    // Batch stuff
    BatchScanTask createBatchScanTask(Task<ArrayList<List<String>>> taskView, Function<Double, Void> updateCallback);

    BatchIndexTask createBatchIndexTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    CreateNlpBatchesFromIndexTask createCreateNlpBatchesFromIndexTask(
        Task<ArrayList<List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument>>> taskView,
        Function<Double, Void> updateCallback);

    BatchNlpTask createBatchNlpTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    BatchScanWithSerializerTask createBatchScanWithSerializerTask(Task<ArrayList<String>> taskView, Function<Double, Void> updateCallback);
    BatchIndexWithSerializerTask createBatchIndexWithSerializerTask(Task<Long> taskView, Function<Double, Void> updateCallback);
    CreateNlpBatchesFromIndexWithSerializerTask createCreateNlpBatchesFromIndexWithSerializerTask(
        Task<ArrayList<String>> taskView, Function<Double, Void> updateCallback);
    BatchNlpWithSerializerTask createBatchNlpWithSerializerTask(Task<Long> taskView, Function<Double, Void> updateCallback);


    GenApiKeyTask createGenApiKey(User user);

    DelApiKeyTask createDelApiKey(User user);

    GetApiKeyTask createGetApiKey(User user);
}
