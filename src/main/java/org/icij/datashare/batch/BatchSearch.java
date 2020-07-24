package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.util.*;

import static java.util.Collections.unmodifiableList;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

public class BatchSearch extends BatchSearchRecord {

    public final LinkedHashMap<String, Integer> queries; // LinkedHashMap keeps insert order
    public final List<String> fileTypes;
    public final List<String> paths;
    public final int fuzziness;
    public final boolean phraseMatches;

    // batch search creation
    public BatchSearch(final Project project, final String name, final String description, final LinkedHashSet<String> queries, User user) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), new Date(), State.QUEUED, user,
                0, false, null, null, 0,false, null);
    }
    public BatchSearch(final Project project, final String name, final String description, final LinkedHashSet<String> queries, User user, boolean published) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), new Date(), State.QUEUED, user, 0, published, null, null, 0,false, null);
    }
    public BatchSearch(final Project project, final String name, final String description, final LinkedHashSet<String> queries, User user, boolean published, List<String> fileTypes, List<String> paths, int fuzziness) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), new Date(), State.QUEUED, user, 0, published, fileTypes, paths, fuzziness,false, null);
    }

    public BatchSearch(final Project project, final String name, final String description, final LinkedHashSet<String> queries, User user, boolean published, List<String> fileTypes, List<String> paths, int fuzziness,boolean phraseMatches) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), new Date(), State.QUEUED, user, 0, published, fileTypes, paths, fuzziness,phraseMatches, null);
    }

    public BatchSearch(final Project project, final String name, final String description, final LinkedHashSet<String> queries, User user, boolean published, List<String> fileTypes, List<String> paths,boolean phraseMatches) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), new Date(), State.QUEUED, user, 0, published, fileTypes, paths, 0,phraseMatches, null);
    }

    public BatchSearch(String uuid, Project project, String name, String description, LinkedHashSet<String> queries, Date date, State state, User user) {
        this(uuid, project, name, description, toLinkedHashMap(queries), date, state, user,
                0, false, null, null, 0,false, null);
    }

    // for tests
    public BatchSearch(final Project project, final String name, final String description, final LinkedHashSet<String> queries, Date date) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), date, State.QUEUED, User.local(),
                0, false, null, null, 0,false, null);
    }

    // retrieved from persistence
    public BatchSearch(String uuid, Project project, String name, String description, LinkedHashMap<String, Integer> queries, Date date, State state, User user,
                       int nbResults, boolean published, List<String> fileTypes, List<String> paths, int fuzziness, boolean phraseMatches, String errorMessage) {
        super(uuid,project,name,description,queries.size(),date,state,user,nbResults,published,errorMessage);
        this.queries = queries;
        this.fileTypes = unmodifiableList(ofNullable(fileTypes).orElse(new ArrayList<>()));
        this.paths = unmodifiableList(ofNullable(paths).orElse(new ArrayList<>()));
        this.fuzziness = fuzziness;
        this.phraseMatches=phraseMatches;
    }

    @JsonIgnore
    public List<String> getQueryList() {return new ArrayList<>(queries.keySet());}

    private static LinkedHashMap<String, Integer> toLinkedHashMap(LinkedHashSet<String> queries) {
        return queries.stream().collect(toMap(identity(), i -> 0,
                (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); },
                LinkedHashMap::new));
    }

    @Override
    public String toString() {
        return "BatchSearch{" + uuid + " name='" + name + '\'' + " (" + state + ")}";
    }
}
