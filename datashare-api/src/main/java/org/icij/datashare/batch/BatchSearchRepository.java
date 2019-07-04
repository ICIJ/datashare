package org.icij.datashare.batch;

import org.icij.datashare.user.User;
import java.util.List;

public interface BatchSearchRepository {
    boolean save(User user, BatchSearch batchSearch);
    List<BatchSearch> get(User user);
}
