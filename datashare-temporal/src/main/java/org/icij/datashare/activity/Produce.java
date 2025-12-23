package org.icij.datashare.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface Produce {
    @ActivityMethod(name = "produce_act")
    int produce(int maxTasks);
}