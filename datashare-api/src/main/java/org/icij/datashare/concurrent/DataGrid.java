package org.icij.datashare.concurrent;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Cluster;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ICountDownLatch;
import org.icij.datashare.DataShare;
import org.icij.datashare.DataShare.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * Distributed Data Structures
 *
 * Created by julien on 1/5/17.
 */
public enum DataGrid {
    INSTANCE("instance"),
    CLIENT  ("client");

    static final Logger LOGGER = LoggerFactory.getLogger(DataGrid.class);

    private final String type;

    private final Path configPath = Paths.get(
            System.getProperty("user.dir"), "src", "main", "resources",
            Paths.get(this.getClass().getPackage().getName().replace(".", "/")).toString(),
            "org/icij/datashare/concurrent/hazelcast.xml"
    );

    private final HazelcastInstance hazelcastInstance;


    DataGrid(String type) {
        this.type = type;
        if (this.type.equals("instance")) {
            Config cfg;
            try {
                cfg = new XmlConfigBuilder(configPath.toString()).build();
            } catch (FileNotFoundException e) {
                cfg = new Config();
            }
            hazelcastInstance = Hazelcast.newHazelcastInstance(cfg);
        } else {
            ClientConfig clientConfig = new ClientConfig();
            hazelcastInstance = HazelcastClient.newHazelcastClient( clientConfig );
        }
    }


    public <T> BlockingQueue<T> getBlockingQueue(String name) {
        return hazelcastInstance.getQueue(name);
    }

    public ICountDownLatch getCountDownLatch(String name) {
        return hazelcastInstance.getCountDownLatch(name);
    }

    public Cluster getCluster() { return hazelcastInstance.getCluster(); }

    public void setLocalMemberRole(DataShare.Stage stage) {
        hazelcastInstance.getCluster().getLocalMember().setStringAttribute("role", stage.toString());
    }

    public boolean awaitMemberJoins(Stage awaitedStage, int timeoutDuration, TimeUnit timeoutUnit) {
        boolean memberHasJoined = false;
        long start = new Date().getTime();
        long now;
        do {
            Set<Stage> awaitedStageMembers = hazelcastInstance.getCluster().getMembers().stream()
                    .filter (member -> ! member.localMember())
                    .map    (member -> Stage.parse(member.getStringAttribute("role")))
                    .filter (Optional::isPresent)
                    .map    (Optional::get)
                    .filter (awaitedStage::equals)
                    .collect(Collectors.toSet());
            if (awaitedStageMembers.isEmpty()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    LOGGER.info("Await member interrupted");
                    Thread.currentThread().interrupt();
                }
            } else {
                memberHasJoined = true;
            }
            now = new Date().getTime();
        } while( ! memberHasJoined && (now - start) < timeoutUnit.toMillis(timeoutDuration) &&
                 ! Thread.currentThread().isInterrupted() );
        return memberHasJoined;
    }

    public boolean awaitMemberJoins(Stage awaitedStage) {
        return awaitMemberJoins(awaitedStage, 60, TimeUnit.MINUTES);
    }

    public void shutdown() {
            hazelcastInstance.shutdown();
    }

}
