package org.icij.datashare.asynctasks;

import java.util.List;
import org.icij.datashare.Stage;

public interface StageProcessor<I, O> {
    Stage stage();
    StageResult process(List<I> batch, StageContext<O> ctx);
}