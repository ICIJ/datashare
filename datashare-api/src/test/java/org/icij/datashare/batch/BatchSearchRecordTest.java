package org.icij.datashare.batch;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.util.Date;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;

public class BatchSearchRecordTest {
    @Test
    public void test_serialize_deserialize() throws JsonProcessingException {
        ObjectMapper typeInclusionMapper = JsonObjectMapper.MAPPER;
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        BatchSearchRecord batchRecord = new BatchSearchRecord(List.of(project("project")), "name", "description", 123, new Date(), uri);
        String json = typeInclusionMapper.writeValueAsString(batchRecord);
        BatchSearchRecord actualBatchRecord = typeInclusionMapper.readValue(json, BatchSearchRecord.class);
        assertThat(batchRecord).isEqualTo(actualBatchRecord);
    }
}