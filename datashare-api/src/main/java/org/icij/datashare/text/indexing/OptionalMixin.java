package org.icij.datashare.text.indexing;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Created by julien on 7/1/16.
 */
final class OptionalMixin {

    @JsonProperty
    private Object value;

    private OptionalMixin() {}

}