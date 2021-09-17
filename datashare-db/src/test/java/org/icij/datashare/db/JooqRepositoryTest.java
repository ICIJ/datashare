package org.icij.datashare.db;


import org.icij.datashare.Note;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.test.DatashareTimeRule;
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
import static org.icij.datashare.UserEvent.Type.DOCUMENT;
import static org.icij.datashare.UserEvent.Type.SEARCH;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.icij.datashare.text.nlp.Pipeline.Type.*;
import static org.icij.datashare.user.User.nullUser;

@RunWith(Parameterized.class)
public class JooqRepositoryTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2021-06-30T12:13:14Z");
    @Rule public DbSetupRule dbRule;
    private JooqRepository repository;

    @Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/test?user=test&password=test")}
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
                NamedEntity.create(PERSON, "mention 1", asList(123L), "doc_id", "root", CORENLP, GERMAN),
                NamedEntity.create(PERSON, "mention 2", asList(321L), "doc_id", "root", CORENLP, ENGLISH));

        repository.create(namedEntities);

        NamedEntity actualNe1 = repository.getNamedEntity(namedEntities.get(0).getId());
        assertThat(actualNe1).isEqualTo(namedEntities.get(0));
        assertThat(actualNe1.getCategory()).isEqualTo(PERSON);
        assertThat(actualNe1.getExtractor()).isEqualTo(CORENLP);
        assertThat(actualNe1.getExtractorLanguage()).isEqualTo(GERMAN);

        assertThat(repository.getNamedEntity(namedEntities.get(1).getId())).isEqualTo(namedEntities.get(1));
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
    public void test_group_unstar_a_document_without_documents() {
        User user = new User("userid");

        assertThat(repository.star(project("prj"), user, asList("id1", "id2", "id3"))).isEqualTo(3);
        assertThat(repository.getStarredDocuments(project("prj"), user)).contains("id1", "id2", "id3");

        assertThat(repository.star(project("prj"), user, singletonList("id1"))).isEqualTo(0);
        assertThat(repository.getStarredDocuments(project("prj2"), user)).isEmpty();

        assertThat(repository.unstar(project("prj"), user,asList("id1", "id2"))).isEqualTo(2);
        assertThat(repository.unstar(project("prj"), user, singletonList("id3"))).isEqualTo(1);
        assertThat(repository.getStarredDocuments(project("prj"), user)).isEmpty();
    }

    @Test
    public void test_group_recommend_a_document_without_documents() {
        User user1 = new User("user1");
        User user2 = new User("user2");

        assertThat(repository.recommend(project("prj"), user1, asList("id1", "id2", "id3"))).isEqualTo(3);
        assertThat(repository.recommend(project("prj"), user2, asList("id1"))).isEqualTo(1);

        Repository.AggregateList<User> recommendations = repository.getRecommendations(project("prj"), asList("id1", "id2", "id4"));
        assertThat(recommendations.aggregates).contains(new Repository.Aggregate<>(user1, 2), new Repository.Aggregate<>(user2, 1));
        assertThat(recommendations.totalCount).isEqualTo(2);

        recommendations = repository.getRecommendations(project("prj"));
        assertThat(recommendations.aggregates).contains(new Repository.Aggregate<>(user1, 3), new Repository.Aggregate<>(user2, 1));
        assertThat(recommendations.totalCount).isEqualTo(3);
        assertThat(repository.getRecommentationsBy(project("prj"),asList(user1,user2))).contains("id1").contains("id2").contains("id3");

        assertThat(repository.unrecommend(project("prj"), user1,asList("id1", "id2"))).isEqualTo(2);
        assertThat(repository.unrecommend(project("prj"), user1, singletonList("id3"))).isEqualTo(1);
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
    public void test_get_recommendations_filter_with_doc_list_and_with_user_properties() {
        final User userFoo = new User("foo" , "test", "foo@bar.org");
        repository.save(userFoo);

        repository.recommend(project("prj"), userFoo, asList("id4"));

        final Repository.Aggregate<User> recommendationEntry = repository.getRecommendations(project("prj"), asList("id4")).aggregates.iterator().next();
        assertThat(recommendationEntry.item.id).isEqualTo("foo");
        assertThat(recommendationEntry.item.name).isEqualTo("test");
        assertThat(recommendationEntry.item.email).isEqualTo("foo@bar.org");
        assertThat(recommendationEntry.count).isEqualTo(1);
    }

    @Test
    public void test_get_recommendations_with_user_properties() {
        final User userBar = new User("bar" , "test", "bar@bar.org");
        repository.save(userBar);

        repository.recommend(project("prj"), userBar, asList("id5", "id6"));

        final Repository.Aggregate<User> recommendationEntry = repository.getRecommendations(project("prj")).aggregates.iterator().next();
        assertThat(recommendationEntry.item.id).isEqualTo("bar");
        assertThat(recommendationEntry.item.name).isEqualTo("test");
        assertThat(recommendationEntry.item.email).isEqualTo("bar@bar.org");
        assertThat(recommendationEntry.count).isEqualTo(2);
    }

    @Test
    public void test_get_unknown_user() {
        assertThat(repository.getUser("unknown")).isNull();
    }

    @Test
    public void test_save_get_user() {
        final User expected = new User(new HashMap<String, Object>() {{
            put("uid", "bar");
            put("provider", "test");
            put("name", "Bar Baz");
            put("email", "bar@foo.com");
            put("password", "pass");
            put("baz", "qux");
        }});
        assertThat(repository.save(expected)).isTrue();

        User actual = repository.getUser("bar");
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.name).isEqualTo(expected.name);
        assertThat(actual.email).isEqualTo(expected.email);
        assertThat(actual.provider).isEqualTo(expected.provider);
        assertThat(actual.details).isEqualTo(expected.details);
    }

    @Test
    public void test_save_or_update_user() {
        final User fistSave = new User(new HashMap<String, Object>() {{
            put("uid", "baz");
            put("provider", "test");
            put("name", "Bar Baz");
            put("email", "baz@foo.com");
        }});
        repository.save(fistSave);
        final User updatedUser = new User(new HashMap<String, Object>() {{
            put("uid", "baz");
            put("provider", "prov");
            put("name", "Baz");
            put("email", "baz@foo.fr");
        }});
        assertThat(repository.save(updatedUser)).isTrue();

        User actual = repository.getUser("baz");
        assertThat(actual).isEqualTo(updatedUser);
        assertThat(actual.name).isEqualTo(updatedUser.name);
        assertThat(actual.email).isEqualTo(updatedUser.email);
        assertThat(actual.provider).isEqualTo(updatedUser.provider);
        assertThat(actual.details).isEqualTo(updatedUser.details);
    }

    @Test
    public void test_save_and_get_user_with_empty_details(){
        User expected = new User("foo", "bar", "mail", "icij", new HashMap<>());
        repository.save(expected);
        User actual = repository.getUser("foo");
        assertThat(actual).isEqualTo(User.localUser("foo"));
        assertThat(actual.email).isEqualTo(expected.email);
        assertThat(actual.provider).isEqualTo(expected.provider);
        assertThat(actual.details).isEqualTo(expected.details);
        assertThat(actual.name).isEqualTo(expected.name);
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
        repository.recommend(project("prj"), user, asList("doc_id"));

        assertThat(repository.deleteAll("prj")).isTrue();
        assertThat(repository.deleteAll("prj")).isFalse();

        assertThat(repository.getDocuments(project("prj"), tag("tag1"), tag("tag2"))).isEmpty();
        assertThat(repository.getStarredDocuments(user)).isEmpty();
        assertThat(repository.getRecommentationsBy(project("prj"),asList(user))).isEmpty();
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

        assertThat(repository.getNotes(project("otherProject"))).isEmpty();
        assertThat(repository.getNotes(project("project"))).containsExactly(note1, note2);
    }

    @Test
    public void test_get_empty_user_history() {
        assertThat(repository.getUserEvents(User.local(), SEARCH, 0, 10)).isEmpty();
    }

    @Test
    public void test_add_get_document_to_user_history() {
        User user = new User("userid");
        User user2 = new User("userid2");
        Date date1 = new Date(new Date().getTime());
        Date date2 = new Date(new Date().getTime() + 100);

        UserEvent userEvent = new UserEvent(user, DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri(), date1, date1);
        UserEvent userEvent2 = new UserEvent(user, DOCUMENT, "doc_name2", Paths.get("doc_uri2").toUri(), date2, date2);
        UserEvent userEvent3 = new UserEvent(user2, DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri());

        assertThat(repository.addToHistory(project("project"), userEvent)).isTrue();
        assertThat(repository.addToHistory(project("project"), userEvent2)).isTrue();
        assertThat(repository.addToHistory(project("project"), userEvent3)).isTrue();
        assertThat(repository.getUserEvents(user, DOCUMENT, 0, 10)).containsSequence(userEvent2,userEvent);
        assertThat(repository.getTotalUserEvents(user, DOCUMENT)).isEqualTo(2);
        assertThat(repository.getUserEvents(user, DOCUMENT, 0, 1)).containsExactly(userEvent2);
        assertThat(repository.getUserEvents(user2, DOCUMENT, 0, 10)).containsExactly(userEvent3);
        assertThat(repository.getTotalUserEvents(user2, DOCUMENT)).isEqualTo(1);
        assertThat(repository.getUserEvents(user, SEARCH, 0, 10)).isEmpty();
    }

    @Test
    public void test_update_user_event() {
        Date date1 = new Date(new Date().getTime());
        Date date2 = new Date(new Date().getTime() + 100);
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", Paths.get("doc_uri").toUri(), date1, date1);
        UserEvent userEvent2 = new UserEvent(User.local(), DOCUMENT, "doc_name", Paths.get("doc_uri").toUri(), date2, date2);

        assertThat(repository.addToHistory(project("project"), userEvent)).isTrue();
        assertThat(repository.addToHistory(project("project"), userEvent2)).isTrue();

        assertThat(repository.getUserEvents(User.local(), DOCUMENT, 0, 10)).containsExactly(userEvent);
    }

    @Test
    public void test_delete_user_event_by_type() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", Paths.get("doc_uri").toUri());
        UserEvent userEvent2 = new UserEvent(User.local(), SEARCH, "search_name", Paths.get("search_uri").toUri());
        repository.addToHistory(project("project"), userEvent);
        repository.addToHistory(project("project"), userEvent2);

        assertThat(repository.getUserEvents(User.local(), DOCUMENT, 0, 10)).containsExactly(userEvent);
        assertThat(repository.deleteUserHistory(User.local(), DOCUMENT)).isTrue();
        assertThat(repository.deleteUserHistory(User.local(), DOCUMENT)).isFalse();
        assertThat(repository.getTotalUserEvents(User.local(),DOCUMENT)).isEqualTo(0);
        assertThat(repository.getUserEvents(User.local(),SEARCH, 0, 10)).containsExactly(userEvent2);
    }

    @Test
    public void test_delete_single_user_event_by_id() {
        Date date = new Date(new Date().getTime());
        repository.addToHistory(project("project"), new UserEvent(User.local(), DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri()));
        repository.addToHistory(project("project"), new UserEvent(User.local(), DOCUMENT, "doc_name2", Paths.get("doc_uri2").toUri()));
        List<UserEvent> userEvents = repository.getUserEvents(User.local(), DOCUMENT, 0, 10);

        assertThat(repository.deleteUserEvent(User.local(), userEvents.get(1).id)).isTrue();
        assertThat(repository.deleteUserEvent(User.local(), userEvents.get(1).id)).isFalse();
        assertThat(repository.getUserEvents(User.local(),DOCUMENT, 0, 10)).containsExactly(userEvents.get(0));
    }

    @Test
    public void test_db_status(){
        assertThat(repository.getHealth()).isTrue();
    }
}
