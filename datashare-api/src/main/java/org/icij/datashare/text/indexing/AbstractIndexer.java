package org.icij.datashare.text.indexing;

import java.util.*;
import static java.util.Collections.nCopies;
import static java.util.Collections.singletonList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.icij.datashare.text.indexing.Indexer.NodeType.LOCAL;
import static org.icij.datashare.function.Predicates.isGE;
import static org.icij.datashare.function.Predicates.notEmptyList;
import static org.icij.datashare.function.Predicates.notEmptyStr;
import static org.icij.datashare.function.ThrowingFunctions.*;


/**
 * Common structure and behavior of Indexer implementations
 *
 * Created by julien on 7/22/16.
 */
public abstract class AbstractIndexer implements Indexer {

    static protected final int DEFAULT_SEARCH_FROM = 0;
    static protected final int DEFAULT_SEARCH_SIZE = 100;
    static protected final int DEFAULT_TIMEOUT_INSEC = 10;


    protected final Logger LOGGER = LogManager.getLogger(getClass());

    // Connect to Local or Remote nodes?
    protected final NodeType nodeType;

    // Hostnames
    protected final List<String> hosts;

    // Hosts ports
    protected final List<Integer> ports;

    // Cluster name
    protected final String cluster;

    // Number of primary shards
    protected final int shards;

    // Number of secondary shards (failover)
    protected final int replicas;


    public AbstractIndexer(Properties properties) {
        nodeType = getProperty(Property.NODE_TYPE.getName(), properties,
                removeSpaces
                        .andThen(NodeType::parse)
                        .andThen(Optional::get))
                .orElse(LOCAL);

        hosts = getProperty(Property.HOSTS.getName(), properties,
                removeSpaces
                        .andThen(splitComma)
                        .andThen(filterElements.apply(notEmptyStr)))
                .filter(notEmptyList)
                .orElse(singletonList(LOCAL.defaultHost()));

        ports = getProperty(Property.PORTS.getName(), properties,
                removeSpaces
                        .andThen(splitComma)
                        .andThen(parseInts))
                .orElse(nCopies(hosts.size(), defaultPort()));

        if (ports.size() != hosts.size()) {
            throw new IllegalArgumentException(
                    String.format("Number of ports (%d) is not equal to number of hosts (%d)",
                            ports.size(), hosts.size()));
        }

        cluster = getProperty(Property.CLUSTER.getName(), properties, removeSpaces)
                .filter(notEmptyStr)
                .orElse(DEFAULT_CLUSTER);
        shards = getProperty(Property.SHARDS.getName(), properties, parseInt)
                .filter(isGE.apply(1))
                .orElse(nodeType.defaultIndexShards());
        replicas = getProperty(Property.REPLICAS.getName(), properties, parseInt)
                .filter(isGE.apply(0))
                .orElse(nodeType.defaultIndexReplicas());
    }


    @Override
    public Indexer.Type getType() {
        return Indexer.Type.fromClassName(getClass().getSimpleName()).get();
    }

    /**
     * Await connection is up and living
     *
     * @return true if connection is up and living; false otherwise
     */
    protected abstract boolean awaitConnectionIsUp();

}
