package org.icij.datashare.db;


import org.icij.datashare.Note;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.icij.datashare.text.nlp.Pipeline.Type.*;
import static org.icij.datashare.user.User.nullUser;

@RunWith(Parameterized.class)
public class JooqRepositoryTest {
    @Rule
    public DbSetupRule dbRule;
    private JooqRepository repository;

    @Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgresql/test?user=test&password=test")}
        });
    }

    public JooqRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createRepository();
    }

    @Test
    public void test_create_document() throws Exception {
        Document document = new Document("id", project("prj"), Paths.get("/path/to/doc"), "content",
                FRENCH, Charset.defaultCharset(),
                "text/plain", new HashMap<String, Object>() {{
                    put("key 1", "value 1");
                    put("key 2", "value 2");
                }},
                Document.Status.INDEXED, Pipeline.set(CORENLP, OPENNLP), 432L);

        repository.create(document);

        Document actual = repository.getDocument(document.getId());
        assertThat(actual).isEqualTo(document);
        assertThat(actual.getMetadata()).isEqualTo(document.getMetadata());
        assertThat(actual.getNerTags()).isEqualTo(document.getNerTags());
        assertThat(actual.getExtractionDate()).isEqualTo(document.getExtractionDate());
        assertThat(actual.getProject()).isEqualTo(project("prj"));
    }

    @Test
    public void test_get_untagged_documents() throws Exception {
        Document coreAndOpenNlp = new Document("idCore", project("prj"), Paths.get("/path/to/coreAndOpenNlp"), "coreAndOpenNlp",
                FRENCH, Charset.defaultCharset(),
                "text/plain", new HashMap<>(),
                Document.Status.INDEXED, Pipeline.set(CORENLP, OPENNLP), 432L);
        Document ixaPipe = new Document("idIxa", project("prj"), Paths.get("/path/to/ixaPipe"), "ixaPipe",
                FRENCH, Charset.defaultCharset(),
                "text/plain", new HashMap<>(),
                Document.Status.INDEXED, Pipeline.set(IXAPIPE), 234L);
        repository.create(coreAndOpenNlp);
        repository.create(ixaPipe);

        assertThat(repository.getDocumentsNotTaggedWithPipeline(project("prj"), IXAPIPE)).containsExactly(coreAndOpenNlp);
        assertThat(repository.getDocumentsNotTaggedWithPipeline(project("prj"), CORENLP)).containsExactly(ixaPipe);
        assertThat(repository.getDocumentsNotTaggedWithPipeline(project("prj"), MITIE)).containsExactly(coreAndOpenNlp, ixaPipe);
    }

    @Test
    public void test_create_named_entity_list() {
        List<NamedEntity> namedEntities = Arrays.asList(
                NamedEntity.create(PERSON, "mention 1", 123, "doc_id", CORENLP, GERMAN),
                NamedEntity.create(PERSON, "mention 2", 321, "doc_id", CORENLP, ENGLISH));

        repository.create(namedEntities);

        NamedEntity actualNe1 = repository.getNamedEntity(namedEntities.get(0).getId());
        assertThat(actualNe1).isEqualTo(namedEntities.get(0));
        assertThat(actualNe1.getCategory()).isEqualTo(PERSON);
        assertThat(actualNe1.getExtractor()).isEqualTo(CORENLP);
        assertThat(actualNe1.getExtractorLanguage()).isEqualTo(GERMAN);

        assertThat(repository.getNamedEntity(namedEntities.get(1).getId())).isEqualTo(namedEntities.get(1));
    }

    @Test
    public void test_star_unstar_a_document_with_join() {
        Document doc = new Document("id", project("prj"), Paths.get("/path/to/docId"), "my doc",
                        FRENCH, Charset.defaultCharset(),
                        "text/plain", new HashMap<>(),
                        Document.Status.INDEXED, Pipeline.set(CORENLP, OPENNLP), 432L);
        repository.create(doc);
        User user = new User("userid");

        assertThat(repository.star(user, doc.getId())).isTrue();
        assertThat(repository.getStarredDocuments(user)).contains(doc);
        assertThat(repository.star(user, doc.getId())).isFalse();

        assertThat(repository.unstar(user, doc.getId())).isTrue();
        assertThat(repository.getStarredDocuments(user)).isEmpty();
        assertThat(repository.unstar(user, doc.getId())).isFalse();
    }

    @Test
    public void test_save_read_project() {
        assertThat(repository.save(new Project("prj", Paths.get("/source"), "10.0.*.*"))).isTrue();
        assertThat(repository.getProject("prj").name).isEqualTo("prj");
        assertThat(repository.getProject("prj").sourcePath.toString()).isEqualTo("/source");
        assertThat(repository.getProject("prj").allowFromMask).isEqualTo("10.0.*.*");
    }

    @Test
    public void test_get_unknown_project() {
        assertThat(repository.getProject("unknown")).isNull();
    }

    @Test
    public void test_group_star_unstar_a_document_without_documents() {
        User user = new User("userid");

        assertThat(repository.star(project("prj"), user, asList("id1", "id2", "id3"))).isEqualTo(3);
        assertThat(repository.getStarredDocuments(project("prj"), user)).contains("id1", "id2", "id3");
        assertThat(repository.getStarredDocuments(project("prj2"), user)).isEmpty();

        assertThat(repository.unstar(project("prj"), user,asList("id1", "id2"))).isEqualTo(2);
        assertThat(repository.unstar(project("prj"), user, singletonList("id3"))).isEqualTo(1);
        assertThat(repository.getStarredDocuments(project("prj"), user)).isEmpty();
    }

    @Test
    public void test_tag_untag_a_document() {
        assertThat(repository.tag(project("prj"), "doc_id", tag("tag1"), tag("tag2"))).isTrue();
        assertThat(repository.getDocuments(project("prj"), tag("tag1"))).containsExactly("doc_id");
        assertThat(repository.getDocuments(project("prj"), tag("tag2"))).containsExactly("doc_id");
        assertThat(repository.getDocuments(project("prj"), tag("tag1"), tag("tag2"))).containsExactly("doc_id");

        assertThat(repository.getDocuments(project("prj2"), tag("tag1"))).isEmpty();
        assertThat(repository.tag(project("prj"), "doc_id", tag("tag1"))).isFalse();

        assertThat(repository.untag(project("prj"), "doc_id", tag("tag1"), tag("tag2"))).isTrue();
        assertThat(repository.getDocuments(project("prj"), tag("tag1"))).isEmpty();
        assertThat(repository.untag(project("prj"), "doc_id", tag("tag1"))).isFalse();
    }

    @Test
    public void test_group_tag_untag_documents() {
        assertThat(repository.tag(project("prj"), asList("doc_id1", "doc_id2"), tag("tag1"), tag("tag2"))).isTrue();
        assertThat(repository.tag(project("prj2"), "doc_id3", tag("tag1"))).isTrue();
        assertThat(repository.getDocuments(project("prj"), tag("tag1"), tag("tag2"))).containsExactly("doc_id1", "doc_id2");

        assertThat(repository.untag(project("prj"), asList("doc_id1", "doc_id2"), tag("tag1"), tag("tag2"))).isTrue();
        assertThat(repository.getDocuments(project("prj"), tag("tag1"))).isEmpty();
        assertThat(repository.getDocuments(project("prj"), tag("tag2"))).isEmpty();

        assertThat(repository.getDocuments(project("prj2"), tag("tag1"))).containsExactly("doc_id3");
    }

    @Test
    public void test_get_tags_of_document() {
        Date creationDate = new Date();

        assertThat(repository.tag(project("prj"), "doc_id", tag("tag1"), new Tag("tag2", new User("foo"), creationDate))).isTrue();

        assertThat(repository.getTags(project("prj"), "doc_id")).contains(tag("tag1"), tag("tag2"));
        assertThat(repository.getTags(project("unknown"), "doc_id")).isEmpty();

        assertThat(repository.getTags(project("prj"), "doc_id").get(0).user).isEqualTo(nullUser());
        assertThat(repository.getTags(project("prj"), "doc_id").get(0).creationDate).isNotNull();
        assertThat(repository.getTags(project("prj"), "doc_id").get(1).user).isEqualTo(new User("foo"));
        assertThat(repository.getTags(project("prj"), "doc_id").get(1).creationDate).isEqualTo(creationDate);
    }

    @Test
    public void test_get_group_tag_of_document() {
        Date creationDate = new Date();
        repository.tag(project("prj"), asList("doc1", "doc2"), new Tag("tag", new User("foo"), creationDate));

        assertThat(repository.getTags(project("prj"), "doc1")).contains(tag("tag"));
        assertThat(repository.getTags(project("prj"), "doc2")).contains(tag("tag"));
    }

    @Test
    public void test_delete_all_project() {
        User user = new User("userid");
        repository.star(project("prj"), user, singletonList("doc_id"));
        repository.tag(project("prj"), "doc_id", tag("tag1"), tag("tag2"));

        assertThat(repository.deleteAll("prj")).isTrue();
        assertThat(repository.deleteAll("prj")).isFalse();

        assertThat(repository.getDocuments(project("prj"), tag("tag1"), tag("tag2"))).isEmpty();
        assertThat(repository.getStarredDocuments(user)).isEmpty();
    }

    @Test
    public void test_create_get_notes() {
        Note note1 = new Note(project("project"), Paths.get("/path"), "this is note 1");
        Note note2 = new Note(project("project"), Paths.get("/path/to/note"), "this is note 2");
        repository.save(note1);
        repository.save(note2);

        assertThat(repository.getNotes(project("project"), "/doc.txt")).isEmpty();
        assertThat(repository.getNotes(project("project"), "/other/path/to/doc.txt")).isEmpty();

        assertThat(repository.getNotes(project("project"), "/path/to/doc.txt")).containsExactly(note1);
        assertThat(repository.getNotes(project("project"), "/path/to/note/for/my/doc.txt")).containsExactly(note1, note2);
    }
}
