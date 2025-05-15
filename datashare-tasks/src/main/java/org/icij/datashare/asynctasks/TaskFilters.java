package org.icij.datashare.asynctasks;

import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.text.StringUtils.getValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.icij.datashare.user.User;

public final class TaskFilters {
    private final List<ArgsFilter> args;
    private final Set<Task.State> states;
    private final String name;
    private final User user;
    private final Integer regexFlags;
    private Map<String, Pattern> argsPatterns = null;
    private Pattern taskNamePattern = null;

    public TaskFilters(List<ArgsFilter> args, Set<Task.State> states, String name, User user, Integer regexFlags) {
        this.args = args;
        this.states = states;
        this.name = name;
        this.user = user;
        this.regexFlags = regexFlags;
    }

    public TaskFilters(List<ArgsFilter> args, Set<Task.State> states, String name, User user) {
        this(args, states, name, user, null);
    }

    public static TaskFilters empty() {
        return new TaskFilters(null, null, null, null);
    }

    public Set<Task.State> getStates() {
        return states;
    }

    public List<ArgsFilter> getArgs() {
        return args;
    }

    public String getName() {
        return name;
    }

    public User getUser() {
        return user;
    }

    public TaskFilters withUser(User taskUser) {
        return new TaskFilters(args, states, name, taskUser, regexFlags);
    }

    public TaskFilters withStates(Set<Task.State> taskStates) {
        return new TaskFilters(args, taskStates, name, user, regexFlags);
    }

    public TaskFilters withNames(String name) {
        return new TaskFilters(args, states, name, user, regexFlags);
    }

    public TaskFilters withArgs(List<ArgsFilter> taskArgs) {
        return new TaskFilters(taskArgs, states, name, user, regexFlags);
    }

    public TaskFilters withFlag(Integer flag) {
        return new TaskFilters(args, states, name, user, flag);
    }

    public record ArgsFilter(String argLocation, String pattern) {
    }

    public boolean filter(Task task) {
        return byName(task.name) && byUser(task.getUser()) && byState(task.getState()) && byArgs(task.args);
    }

    private boolean byState(Task.State taskState) {
        return Optional.ofNullable(states).map(expectedStates -> {
            if (!expectedStates.isEmpty()) {
                return expectedStates.contains(taskState);
            }
            return true;
        }).orElse(true);
    }

    private boolean byUser(User taskUser) {
        return Optional.ofNullable(this.user).map(u -> u.equals(taskUser)).orElse(true);
    }

    private boolean byName(String taskName) {
        return Optional.ofNullable(getNamePattern()).map(p -> p.matcher(taskName).find()).orElse(true);
    }

    private boolean byArgs(Map<String, Object> taskArgs) {
        return Optional.ofNullable(getArgsPatterns())
            .map(patterns -> {
                return patterns.entrySet()
                                .stream()
                                .allMatch(e -> {
                                    return e.getValue()
                                            .matcher(String.valueOf(getValue(taskArgs, e.getKey())))
                                            .find();
                                });
            })
            .orElse(true);
    }

    private Pattern getNamePattern() {
        if (name == null) {
            return null;
        }
        if (taskNamePattern == null) {
            taskNamePattern = regexFlags == null ? Pattern.compile(name) : Pattern.compile(name, regexFlags);
        }
        return taskNamePattern;
    }

    private Map<String, Pattern> getArgsPatterns() {
        if (args == null || args.isEmpty()) {
            return null;
        }
        if (argsPatterns == null) {
            argsPatterns = args.stream().collect(toMap(e -> e.argLocation, e -> {
                if (regexFlags != null) {
                    return Pattern.compile(e.pattern, regexFlags);
                }
                return Pattern.compile(e.pattern);
            }));
        }
        return argsPatterns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskFilters filters = (TaskFilters) o;
        return Objects.equals(args, filters.args) && Objects.equals(states, filters.states)
            && Objects.equals(name, filters.name) && Objects.equals(user, filters.user)
            && Objects.equals(regexFlags, filters.regexFlags) && Objects.equals(argsPatterns,
            filters.argsPatterns) && Objects.equals(taskNamePattern, filters.taskNamePattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(args, states, name, user, regexFlags, argsPatterns, taskNamePattern);
    }

}
