package org.icij.datashare.tasks;

public class TestSleepingTask extends TestTask {
    public TestSleepingTask(int value) {
        super(value);
    }

    @Override
    public Integer call() throws Exception {
        callThread = Thread.currentThread();
        int ret = super.call();
        try {
            Thread.sleep(ret);
            return ret;
        } catch (InterruptedException iex) {
            throw new CancelException(cancelTaskId);
        }
    }
}
