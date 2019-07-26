package org.icij.datashare.batch;

import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;

import java.util.List;

public interface BatchSearchRepository {
    boolean save(User user, BatchSearch batchSearch);
    boolean saveResults(String batchSearchId, String query, List<Document> documents);
    boolean setState(String batchSearchId, BatchSearch.State state);

    List<BatchSearch> get(User user);
    List<BatchSearch> getQueued();
    List<SearchResult> getResults(User user, String batchSearchId);
    BatchSearch get(User user, String batchId);
}
