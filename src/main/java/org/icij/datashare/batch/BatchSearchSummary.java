package org.icij.datashare.batch;

import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.util.*;

import static java.util.Collections.unmodifiableList;
import static java.util.Optional.ofNullable;

public class BatchSearchSummary {

    public final String uuid;
    public final boolean published;
    public final Project project;
    public final String name;
    public final String description;
    public final User user;
    private final Date date;
    private final int nbQueries;
    public final List<String> fileTypes;
    public final List<String> paths;
    public final int fuzziness;
    public final boolean phraseMatches;
    public final int nbResults;
    public final String errorMessage;

    public BatchSearchSummary(BatchSearch batchSearch){
        uuid = batchSearch.uuid;
        published = batchSearch.published;
        project = batchSearch.project;
        name = batchSearch.name;
        description = batchSearch.description;
        user = batchSearch.user;
        date = batchSearch.getDate();
        fileTypes = batchSearch.fileTypes;
        paths = batchSearch.paths;
        fuzziness = batchSearch.fuzziness;
        phraseMatches = batchSearch.phraseMatches;
        nbResults = batchSearch.nbResults;
        errorMessage = batchSearch.errorMessage;
        nbQueries = batchSearch.queries.size();

    }

    
}
