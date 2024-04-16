package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;

public class ElasticSearchAdapterException extends RuntimeException{
    private ElasticSearchAdapterException(String jsonCause) {
        super(jsonCause);
    }

    public static Exception createFrom(ElasticsearchException esEx) {
        return new ElasticSearchAdapterException(esEx.response().error().rootCause().stream().findFirst().orElse(esEx.error()).reason());
    }
}
