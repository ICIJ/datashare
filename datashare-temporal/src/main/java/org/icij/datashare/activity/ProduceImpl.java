package org.icij.datashare.activity;

import java.util.Random;

public class ProduceImpl implements Produce {
    @Override
    public int produce(int maxTasks) {
        return new Random().nextInt(maxTasks);
    }
}