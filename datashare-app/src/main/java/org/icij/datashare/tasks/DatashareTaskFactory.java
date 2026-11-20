package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface DatashareTaskFactory extends org.icij.datashare.asynctasks.TaskFactory {
    /** Class names of the return types of the (Task, Function) factory methods, computed once. */
    Set<String> TASK_NAMES = Collections.unmodifiableSet(Arrays.stream(DatashareTaskFactory.class.getDeclaredMethods())
                                                               .filter(m -> Arrays.equals(m.getParameterTypes(),
                                                                                          new Class<?>[] {Task.class,
                                                                                                  Function.class}))
                                                               .map(m -> m.getReturnType().getName())
                                                               .collect(Collectors.toSet()));

    BatchSearchRunner createBatchSearchRunner(Task<?> taskView, Function<Double, Void> updateCallback);

    BatchDownloadRunner createBatchDownloadRunner(Task<?> taskView, Function<Double, Void> updateCallback);

    ScanTask createScanTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    IndexTask createIndexTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    ScanIndexTask createScanIndexTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    ScanQueryTask createScanQueryTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    CategorizeTask createCategorizeTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    ExtractNlpTask createExtractNlpTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    EnqueueFromIndexTask createEnqueueFromIndexTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    CreateNlpBatchesFromIndex createBatchEnqueueFromIndexTask(Task<LinkedList<String>> taskView,
                                                              Function<Double, Void> updateCallback);

    BatchNlpTask createBatchNlpTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    DeduplicateTask createDeduplicateTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    ArtifactTask createArtifactTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    LanguageDetectTask createLanguageDetectTask(Task<Long> taskView, Function<Double, Void> updateCallback);

    StructuredEntityExtractionTask createStructuredEntityExtractionTask(Task<StructuredEntityExtractionResult> taskView,
                                                                        Function<Double, Void> updateCallback);

    GenApiKeyTask createGenApiKey(User user, @Nullable Date expirationDate);

    GenApiKeyTask createGenApiKey(User user, @Nullable Date expirationDate, List<String> projects);

    DelApiKeyTask createDelApiKey(User user);

    GetApiKeyTask createGetApiKey(User user);

    GrantAdminPolicyTask createGrantAdminPolicyTask(User user);
}
