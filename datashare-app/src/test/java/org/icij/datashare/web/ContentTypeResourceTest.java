package org.icij.datashare.web;

import com.fasterxml.jackson.core.type.TypeReference;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.ContentTypeCategory;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ContentTypeResourceTest extends AbstractProdWebServerTest {

    @Before
    public void setUp() {
        configure(routes -> routes.add(new ContentTypeResource()));
    }

    @Test
    public void test_group_by_categories_with_audio_and_video() throws Exception {
        String content = post("/api/contentType/categories", "[\"audio/mp3\", \"video/mp4\"]").response().content();
        Map<ContentTypeCategory, List<String>> result = JsonObjectMapper.readValue(content, new TypeReference<>() {});

        assertThat(result).hasSize(2);
        assertThat(result.get(ContentTypeCategory.AUDIO)).containsExactly("audio/mp3");
        assertThat(result.get(ContentTypeCategory.VIDEO)).containsExactly("video/mp4");
    }

    @Test
    public void test_group_by_categories_with_unknown_content_type() throws Exception {
        String content = post("/api/contentType/categories", "[\"application/unknown\"]").response().content();
        Map<ContentTypeCategory, List<String>> result = JsonObjectMapper.readValue(content, new TypeReference<>() {});

        assertThat(result).hasSize(1);
        assertThat(result.get(ContentTypeCategory.OTHER)).containsExactly("application/unknown");
    }

    @Test
    public void test_group_by_categories_with_empty_list() throws Exception {
        String content = post("/api/contentType/categories", "[]").response().content();
        Map<ContentTypeCategory, List<String>> result = JsonObjectMapper.readValue(content, new TypeReference<>() {});

        assertThat(result).isEmpty();
    }

    @Test
    public void test_group_by_categories_with_image() throws Exception {
        String content = post("/api/contentType/categories", "[\"image/png\"]").response().content();
        Map<ContentTypeCategory, List<String>> result = JsonObjectMapper.readValue(content, new TypeReference<>() {});

        assertThat(result).hasSize(1);
        assertThat(result.get(ContentTypeCategory.IMAGE)).containsExactly("image/png");
    }

    @Test
    public void test_group_by_categories_mixed() throws Exception {
        String content = post("/api/contentType/categories", "[\"audio/mp3\", \"application/unknown\", \"image/jpeg\"]").response().content();
        Map<ContentTypeCategory, List<String>> result = JsonObjectMapper.readValue(content, new TypeReference<>() {});

        assertThat(result).hasSize(3);
        assertThat(result.get(ContentTypeCategory.AUDIO)).containsExactly("audio/mp3");
        assertThat(result.get(ContentTypeCategory.OTHER)).containsExactly("application/unknown");
        assertThat(result.get(ContentTypeCategory.IMAGE)).containsExactly("image/jpeg");
    }
}
