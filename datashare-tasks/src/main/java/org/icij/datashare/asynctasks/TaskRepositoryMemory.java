package org.icij.datashare.asynctasks;

import java.util.concurrent.ConcurrentHashMap;

public class TaskRepositoryMemory extends ConcurrentHashMap<String, Task<?>> implements TaskRepository { }
