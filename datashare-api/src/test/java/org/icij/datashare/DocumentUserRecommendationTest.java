package org.icij.datashare;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;


public class DocumentUserRecommendationTest {
    @Test
    public void serialize_document_user_recommendation() throws JsonProcessingException {
        Project project = Project.project("uber-files");
        Document document = DocumentBuilder.createDoc("foo").with(project).with(Paths.get("/tmp/file.txt")).build();
        User user = new User("bar", "Jane Doe", "jdoe@icij.org");

        DocumentUserRecommendation recommendation = new DocumentUserRecommendation(document, project, user);

        assertThat(JsonObjectMapper.writeValueAsString(recommendation))
                .contains("\"name\":\"uber-files\"")
                .contains("\"id\":\"foo\"")
                .contains("\"id\":\"bar\"");
    }

    @Test
    public void deserialize_document_user_recommendation() throws IOException {
        String json = "{\"project\":\"uber-files\",\"user\":{\"id\":\"bar\",\"name\":\"Jane Doe\",\"details\":{}},\"document\":{\"id\":\"foo\"}}";
        DocumentUserRecommendation recomendation = JsonObjectMapper.readValue(json, DocumentUserRecommendation.class);
        assertThat(recomendation.project.getId()).isEqualTo("uber-files");
        assertThat(recomendation.document.getId()).isEqualTo("foo");
        assertThat(recomendation.user.getId()).isEqualTo("bar");
    }
}
