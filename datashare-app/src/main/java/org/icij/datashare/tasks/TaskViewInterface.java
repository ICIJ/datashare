package org.icij.datashare.tasks;

import org.icij.datashare.user.User;

public interface TaskViewInterface<V> {
    public enum State {CREATED, QUEUING, RUNNING, RETRY, ERROR, DONE, CANCELLED;}
    public V getResult();

    public V getResult(boolean sync);

    public double getProgress();

    public TaskView.State getState();

    public String getError();

    public User getUser();

    // TODO: fix this and put the user back...
    public String getName();
}
