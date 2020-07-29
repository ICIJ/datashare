package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

public class DeliverableRegistry<T extends Entity> {
    private final Map<String, T> deliverableMap;

    public DeliverableRegistry(@JsonProperty("deliverableList") List<T> deliverableList) {
        this.deliverableMap = Collections.unmodifiableMap(deliverableList.stream().collect(Collectors.toMap(T::getId, p -> p)));
    }

    public Set<T> get() {
        return new HashSet<>(deliverableMap.values());
    }

    public T get(String deliverableId) {
        return ofNullable(deliverableMap.get(deliverableId)).orElseThrow(() -> new UnknownDeliverableException(deliverableId));
    }

    public static class UnknownDeliverableException extends NullPointerException {
        public UnknownDeliverableException(String deliverableId) {
            super("cannot find deliverable with id " + deliverableId + " in the registry");
        }
    }
}
