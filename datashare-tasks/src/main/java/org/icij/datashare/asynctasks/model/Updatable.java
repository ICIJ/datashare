package org.icij.datashare.asynctasks.model;

import java.util.Date;

public interface Updatable extends Created {
    Date getUpdatedAt();
    void setUpdateAt(Date updatedAt);
}
