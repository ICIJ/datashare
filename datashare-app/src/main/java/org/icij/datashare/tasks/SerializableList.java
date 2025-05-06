package org.icij.datashare.tasks;


import java.io.Serializable;
import java.util.ArrayList;

public class SerializableList<V extends Serializable> extends ArrayList<V> implements Serializable {
}
