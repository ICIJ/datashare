package org.icij.datashare.extract;

import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import com.google.inject.Inject;
import org.icij.extract.report.ReportMap;
import org.redisson.api.RKeys;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Singleton
public class RedisDocumentCollectionFactory <T> implements DocumentCollectionFactory<T> {
    PropertiesProvider propertiesProvider;
    RedissonClient redissonClient;

    @Inject
    public RedisDocumentCollectionFactory(final PropertiesProvider propertiesProvider, RedissonClient redissonClient) {
        this.propertiesProvider = propertiesProvider;
        this.redissonClient = redissonClient;
    }

    @Override
    public DocumentQueue<T> createQueue(String queueName, Class<T> clazz) {
        return new RedisUserDocumentQueue<>(propertiesProvider, redissonClient, queueName, clazz);
    }

    @Override
    public ReportMap createMap(String mapName) {
        return new RedisUserReportMap(propertiesProvider, redissonClient, mapName);
    }

    @Override
    public List<DocumentQueue<T>> getQueues(String wildcardMatcher, Class<T> clazz) {
        RKeys keys = redissonClient.getKeys();
        Iterable<String> iterable = keys.getKeysByPattern(wildcardMatcher, 100);
        return StreamSupport
                .stream(iterable.spliterator(), false)
                .filter(k -> keys.getType(k) == RType.LIST)
                .map(k -> createQueue(k, clazz))
                .collect(Collectors.toList());
    }


    @Override
    public List<DocumentQueue<T>> getQueues(Class<T> clazz) {
        return getQueues("*", clazz);
    }
}
