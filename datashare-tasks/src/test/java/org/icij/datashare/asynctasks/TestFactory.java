package org.icij.datashare.asynctasks;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestFactory implements TaskFactory {
    private static final Logger logger = LoggerFactory.getLogger(TestFactory.class);

    public static Callable<String> createHelloWorld(Task taskView, Function<Double, String> progress) {
        return new HelloWorld(taskView, progress);
    }

    public SleepForever createSleepForever(Task taskView, Function<Double, String> progress) {
        return new SleepForever(taskView, progress);
    }

    public AnotherSleepForever createAnotherSleepForever(Task taskView, Function<Double, String> progress) {
        return new AnotherSleepForever(taskView, progress);
    }

    public Sleep createSleep(Task taskView, Function<Double, String> progress) {
        return new Sleep(taskView, progress);
    }

    public static class HelloWorld implements Callable<String> {
        private final Function<Double, String> progress;
        private final String greeted;

        public HelloWorld(Task taskView, Function<Double, String> progress) {
            this.progress = progress;
            this.greeted = (String) Objects.requireNonNull(taskView.args.get("greeted"), "missing greeted parameter");
        }

        @Override
        public String call() throws Exception {
            logger.debug("Getting ready to greet {}", greeted);
            progress.apply(0.);
            String hello = "Hello";
            progress.apply(0.5);
            hello += " " + greeted + "!";
            progress.apply(1.);
            logger.debug("Politely greeted {}", greeted);
            return hello;
        }
    }

    @TaskGroup(TaskGroupType.Test)
    public static class Sleep implements Callable<Integer> {
        private final Function<Double, String> progress;
        private final int duration;


        public Sleep(Task taskView, Function<Double, String> progress) {
            this.progress = progress;
            this.duration = (int) taskView.args.get("duration");
        }
        @Override
        public Integer call() throws Exception {
            Thread.sleep(duration);
            return duration;
        }
    }

    @TaskGroup(TaskGroupType.Test)
    public static class SleepForever implements Callable<String>, CancellableTask {
        final Function<Double, String> progress;
        Boolean requeue = null;
        Thread taskThread;

        SleepForever(Task taskView, Function<Double, String> progress) {
            this.progress = progress;
        }

        @Override
        public void cancel(boolean requeue) {
            this.requeue = requeue;
        }

        @Override
        public String call() throws InterruptedException {
            taskThread = Thread.currentThread();
            progress.apply(0.1); // Set the progress to mark the task as RUNNING
            while (true) {
                if (this.requeue != null) {
                    throw new CancelException(this.requeue);
                }
                Thread.sleep(100);
            }
        }
    }

    @TaskGroup(TaskGroupType.Test)
    public static class AnotherSleepForever extends SleepForever {
        AnotherSleepForever(Task taskView, Function<Double, String> progress) {
            super(taskView, progress);
        }
    }
}
