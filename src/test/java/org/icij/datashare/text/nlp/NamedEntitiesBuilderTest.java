package org.icij.datashare.text.nlp;

import java.util.Map;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntitiesBuilder;
import org.icij.datashare.text.NamedEntity;
import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.NamedEntity.Category.LOCATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.icij.datashare.text.nlp.Pipeline.Type.EMAIL;

public class NamedEntitiesBuilderTest {
    @Test
    public void test_build_empty() {
        assertThat(new NamedEntitiesBuilder(EMAIL, "docId", Language.ENGLISH).build()).isEmpty();
    }

    @Test
    public void test_one_mention() {
        List<NamedEntity> namedEntities = new NamedEntitiesBuilder(CORENLP, "docId", Language.ENGLISH).
                add(PERSON, "mention", 12L).add(PERSON, "mention", 124L).
                build();
        assertThat(namedEntities).hasSize(1);
        NamedEntity namedEntity = namedEntities.get(0);
        assertThat(namedEntity.getCategory()).isEqualTo(PERSON);
        assertThat(namedEntity.getExtractor()).isEqualTo(CORENLP);
        assertThat(namedEntity.getDocumentId()).isEqualTo("docId");
        assertThat(namedEntity.getExtractorLanguage()).isEqualTo(Language.ENGLISH);
        assertThat(namedEntity.getOffsets()).containsExactly(12L, 124L);
    }

    @Test
    public void test_three_mentions() {
        List<NamedEntity> namedEntities = new NamedEntitiesBuilder(CORENLP, "docId", Language.ENGLISH).
                add(PERSON, "mention1", 12L).add(PERSON, "mention2", 124L).
                add(NamedEntity.Category.LOCATION, "mention1", 255L).build();
        assertThat(namedEntities).hasSize(3);
        assertThat(namedEntities.get(0).getMention()).isEqualTo("mention1");
        assertThat(namedEntities.get(0).getOffsets()).containsExactly(12L);
        assertThat(namedEntities.get(1).getMention()).isEqualTo("mention2");
        assertThat(namedEntities.get(1).getOffsets()).containsExactly(124L);
        assertThat(namedEntities.get(2).getMention()).isEqualTo("mention1");
        assertThat(namedEntities.get(2).getOffsets()).containsExactly(255L);
        assertThat(namedEntities.get(2).getCategory()).isEqualTo(LOCATION);
    }

    @Test
    public void test_ne_with_root() {
        NamedEntity namedEntity = new NamedEntitiesBuilder(CORENLP, "docId", Language.ENGLISH).
                add(PERSON, "mention1", 12L).withRoot("rootId").build().get(0);
        assertThat(namedEntity.getRootDocument()).isEqualTo("rootId");
    }

    @Test
    public void test_ne_with_metadata() {
        Map<String, Object> meta = Map.of("some", "metadata");
        NamedEntity namedEntity = new NamedEntitiesBuilder(CORENLP, "docId", Language.ENGLISH).
                add(PERSON, "mention1", 12L).withMetadata(meta).build().get(0);
        assertThat(namedEntity.getMetadata()).isEqualTo(meta);
    }
}