package org.icij.datashare.asynctasks;

public record StageResult(long updated, long unchanged, long skipped, long failed) {
    public static final StageResult EMPTY = new StageResult(0, 0, 0, 0);

    public StageResult plus(StageResult o) {
        return new StageResult(updated + o.updated, unchanged + o.unchanged, skipped + o.skipped, failed + o.failed);
    }
}
