package org.icij.datashare.asynctasks.temporal;

import java.util.concurrent.Callable;

public class DoNothingTask implements Callable<String> {
    @Override
    public String call() {
        return "";
    }
}
