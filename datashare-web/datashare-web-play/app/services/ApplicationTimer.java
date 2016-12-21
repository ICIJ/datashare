package services;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import javax.inject.*;
import play.Logger;
import play.inject.ApplicationLifecycle;

@Singleton
public class ApplicationTimer {

    private final Clock clock;
    private final ApplicationLifecycle appLifecycle;
    private final Instant start;

    @Inject
    public ApplicationTimer(Clock clock, ApplicationLifecycle appLifecycle) {
        this.clock = clock;
        this.appLifecycle = appLifecycle;
        // This code is called when the application starts.
        start = clock.instant();
        Logger.info("ApplicationTimer demo: Starting application at " + start);

        // When the application starts, register a stop hook with the
        // ApplicationLifecycle object. The code inside the stop hook will
        // be run when the application stops.
        appLifecycle.addStopHook(() -> {
            Instant stop = clock.instant();
            Long runningTime = stop.getEpochSecond() - start.getEpochSecond();
            Logger.info("ApplicationTimer demo: Stopping application at " + clock.instant() + " after " + runningTime + "s.");
            return CompletableFuture.completedFuture(null);
        });
    }

}
