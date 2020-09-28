package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

public class DeliverableRegistry<T extends Deliverable> {
    private final Map<String, T> deliverableMap;

    public DeliverableRegistry(@JsonProperty("deliverableList") List<T> deliverableList) {
        this.deliverableMap = Collections.unmodifiableMap(deliverableList.stream().collect(Collectors.toMap(T::getId, p -> p)));
    }

    public Set<T> get() {
        return search(".*");
    }

    public T get(String deliverableId) {
        return ofNullable(deliverableMap.get(deliverableId)).orElseThrow(() -> new UnknownDeliverableException(deliverableId));
    }

    public Set<T> search(String patternString){
        Pattern pattern = Pattern.compile(patternString,Pattern.CASE_INSENSITIVE);
        return new HashSet<>(deliverableMap.values()).stream().filter(d -> pattern.matcher(d.getId()).find()
                || pattern.matcher(d.getName()).find() || pattern.matcher(d.getDescription()).find()).collect(Collectors.toSet());
    }

    public static class UnknownDeliverableException extends NullPointerException {
        public UnknownDeliverableException(String deliverableId) {
            super("cannot find deliverable with id " + deliverableId + " in the registry");
        }
    }
}
