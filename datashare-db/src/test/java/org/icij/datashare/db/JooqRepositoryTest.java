package org.icij.datashare.db;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.DocumentUserRecommendation;
import org.icij.datashare.Note;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.user.User;
import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.UserEvent.Type.DOCUMENT;
import static org.icij.datashare.UserEvent.Type.SEARCH;
import static org.icij.datashare.db.tables.UserHistory.USER_HISTORY;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.icij.datashare.text.nlp.Pipeline.Type.OPENNLP;
import static org.icij.datashare.text.nlp.Pipeline.Type.SPACY;
import static org.icij.datashare.text.nlp.Pipeline.Type.TEST;
import static org.icij.datashare.user.User.nullUser;

@RunWith(Parameterized.class)
public class JooqRepositoryTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2021-06-30T12:13:14Z");
    @Rule public DbSetupRule dbRule;
    private final JooqRepository repository;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://" + EnvUtils.resolveHost("postgres") + "/dstest?user=dstest&password=test")}
        });
    }

    public JooqRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createRepository();
        rulesToClose.add(dbRule);
    }

    @AfterClass
    public static void shutdownPools() {
        for (DbSetupRule rule : rulesToClose) {
            rule.shutdown();
        }
    }

    @Test
    public void test_create_document() {
        Document document = DocumentBuilder.createDoc("id")
                .with(project("prj"))
                .with(Paths.get("/path/to/doc"))
                .with("content")
                .with(FRENCH).with(Charset.defaultCharset())
                .with("text/plain")
                .with(new HashMap<>() {{
                    put("key 1", "value 1");
                    put("key 2", "value 2");
                }})
                .with(Document.Status.INDEXED)
                .with(CORENLP, OPENNLP)
                .withContentLength(432L).build();

        repository.create(document);

        Document actual = repository.getDocument(document.getId());
        assertThat(actual).isEqualTo(document);
        assertThat(actual.getMetadata()).isEqualTo(document.getMetadata());
        assertThat(actual.getNerTags()).isEqualTo(document.getNerTags());
        assertThat(actual.getExtractionDate()).isEqualTo(document.getExtractionDate());
        assertThat(actual.getProject()).isEqualTo(project("prj"));
    }

    @Test
    public void test_get_untagged_documents() {
        Document coreAndOpenNlp = DocumentBuilder.createDoc("idCore")
                .with(project("prj"))
                .with(Paths.get("/path/to/coreAndOpenNlp"))
                .with("coreAndOpenNlp")
                .with(FRENCH).with(Charset.defaultCharset())
                .with("text/plain")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .with(CORENLP, OPENNLP)
                .withContentLength(432L).build();
        Document spacyPipe = DocumentBuilder.createDoc("idSpacy")
                .with(project("prj"))
                .with(Paths.get("/path/to/spacyPipe"))
                .with("spacyPipe")
                .with(FRENCH).with(Charset.defaultCharset())
                .with("text/plain")
                .with(new HashMap<>())
                .with(Document.Status.INDEXED)
                .with(SPACY)
                .withContentLength(234L).build();

        repository.create(coreAndOpenNlp);
        repository.create(spacyPipe);

        assertThat(repository.getDocumentsNotTaggedWithPipeline(project("prj"), SPACY)).containsExactly(coreAndOpenNlp);
        assertThat(repository.getDocumentsNotTaggedWithPipeline(project("prj"), CORENLP)).containsExactly(spacyPipe);
        assertThat(repository.getDocumentsNotTaggedWithPipeline(project("prj"), TEST)).containsExactly(coreAndOpenNlp, spacyPipe);
    }

    @Test
    public void test_create_named_entity_list() {
        List<NamedEntity> namedEntities = Arrays.asList(
                NamedEntity.create(PERSON, "mention 1", List.of(123L), "doc_id", "root", CORENLP, GERMAN),
                NamedEntity.create(PERSON, "mention 2", List.of(321L), "doc_id", "root", CORENLP, ENGLISH));

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
    public void test_get_list_project_by_ids() {
        repository.save(new Project("foo"));
        repository.save(new Project("bar"));
        List<String> projectIds = new ArrayList<>(List.of("foo","bar"));
        List<Project> projects = repository.getProjects(projectIds);
        assertThat(projects).hasSize(2);
        List<String> projectNames = projects.stream().map(Project::getName).collect(Collectors.toList());
        assertThat("foo").isIn(projectNames);
        assertThat("bar").isIn(projectNames);
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
        assertThat(repository.recommend(project("prj"), user2, List.of("id1"))).isEqualTo(1);

        Repository.AggregateList<User> recommendations = repository.getRecommendations(project("prj"), asList("id1", "id2", "id4"));
        assertThat(recommendations.aggregates).contains(new Repository.Aggregate<>(user1, 2), new Repository.Aggregate<>(user2, 1));
        assertThat(recommendations.totalCount).isEqualTo(2);

        recommendations = repository.getRecommendations(project("prj"));
        assertThat(recommendations.aggregates).contains(new Repository.Aggregate<>(user1, 3), new Repository.Aggregate<>(user2, 1));
        assertThat(recommendations.totalCount).isEqualTo(3);
        assertThat(repository.getRecommendationsBy(project("prj"), asList(user1, user2))).contains("id1").contains("id2").contains("id3");

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

        repository.recommend(project("prj"), userFoo, List.of("id4"));

        final Repository.Aggregate<User> recommendationEntry = repository.getRecommendations(project("prj"), List.of("id4")).aggregates.iterator().next();
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
    public void test_get_all_document_user_recommendations() {
        final User janeDoe = new User("jdoe" , "Jane Doe", "jdoe@icij.org");
        final Project projectFoo = new Project("foo");
        repository.save(janeDoe);
        repository.save(projectFoo);
        repository.recommend(projectFoo, janeDoe, asList("id5", "id6"));
        List<DocumentUserRecommendation> recommendations = repository.getDocumentUserRecommendations(0, 50);
        assertThat(recommendations).hasSize(2);
        assertThat(recommendations.get(0).project.name).isEqualTo("foo");
        assertThat(recommendations.get(0).user.id).isEqualTo("jdoe");
        assertThat(recommendations.get(0).user.name).isEqualTo("Jane Doe");
    }

    @Test
    public void test_get_all_document_user_recommendations_without_user_inventory() {
        final User janeDoe = new User("jdoe" , "Jane Doe", "jdoe@icij.org");
        final Project projectFoo = new Project("foo");
        repository.save(projectFoo);
        repository.recommend(projectFoo, janeDoe, asList("id5", "id6"));
        List<DocumentUserRecommendation> recommendations = repository.getDocumentUserRecommendations(0, 50);
        assertThat(recommendations).hasSize(2);
        assertThat(recommendations.get(0).project.name).isEqualTo("foo");
        assertThat(recommendations.get(0).user.id).isEqualTo("jdoe");
        assertThat(recommendations.get(0).user.name).isEqualTo(null);
    }

    @Test
    public void test_get_all_document_user_recommendations_with_size() {
        final User janeDoe = new User("jdoe" , "Jane Doe", "jdoe@icij.org");
        final Project projectFoo = new Project("foo");
        repository.save(janeDoe);
        repository.save(projectFoo);
        repository.recommend(projectFoo, janeDoe, asList("id5", "id6"));
        List<DocumentUserRecommendation> recommendations = repository.getDocumentUserRecommendations(0, 1);
        assertThat(recommendations).hasSize(1);
    }

    @Test
    public void test_get_all_document_user_recommendations_with_project() {
        final User janeDoe = new User("jdoe" , "Jane Doe", "jdoe@icij.org");
        final Project projectFoo = new Project("foo");
        final Project projectBar = new Project("bar");
        repository.save(janeDoe);
        repository.save(projectFoo);
        repository.save(projectBar);
        repository.recommend(projectFoo, janeDoe, asList("id5", "id6"));
        repository.recommend(projectBar, janeDoe, asList("id7", "id8"));

        List<DocumentUserRecommendation> fooRecommendations = repository.getDocumentUserRecommendations(0, 50, List.of(projectFoo));
        assertThat(fooRecommendations).hasSize(2);
        assertThat(fooRecommendations.get(0).project.name).isEqualTo("foo");

        List<DocumentUserRecommendation> barRecommendations = repository.getDocumentUserRecommendations(0, 50, List.of(projectBar));
        assertThat(barRecommendations).hasSize(2);
        assertThat(barRecommendations.get(0).project.name).isEqualTo("bar");
    }

    @Test
    public void test_get_unknown_user() {
        assertThat(repository.getUser("unknown")).isNull();
    }

    @Test
    public void test_save_get_user() {
        final User expected = new User(new HashMap<>() {{
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
        final User fistSave = new User(new HashMap<>() {{
            put("uid", "baz");
            put("provider", "test");
            put("name", "Bar Baz");
            put("email", "baz@foo.com");
        }});
        repository.save(fistSave);
        final User updatedUser = new User(new HashMap<>() {{
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
    public void test_get_user_events_items() {
        User user = new User("userid");
        UserEvent userEvent1 = new UserEvent(user, DOCUMENT, "doc_id", Paths.get("doc_uri").toUri());
        UserEvent userEvent2 = new UserEvent(user, SEARCH, "search_id", Paths.get("searcb_uri").toUri());
        repository.addToUserHistory(singletonList(project("prj")), userEvent1);
        repository.addToUserHistory(singletonList(project("prj")), userEvent2);

        assertThat(repository.getUserEvents(user)).containsExactly(userEvent1, userEvent2);
    }

    @Test
    public void test_delete_all_project() {
        User user = new User("userid");
        repository.star(project("prj"), user, singletonList("doc_id"));
        repository.tag(project("prj"), "doc_id", tag("tag1"), tag("tag2"));
        repository.recommend(project("prj"), user, List.of("doc_id"));
        repository.addToUserHistory(singletonList(project("prj")), new UserEvent(user, DOCUMENT, "doc_id", Paths.get("doc_uri").toUri()));

        assertThat(repository.deleteAll("prj")).isTrue();
        assertThat(repository.deleteAll("prj")).isFalse();

        assertThat(repository.getDocuments(project("prj"), tag("tag1"), tag("tag2"))).isEmpty();
        assertThat(repository.getStarredDocuments(user)).isEmpty();
        assertThat(repository.getRecommendationsBy(project("prj"), List.of(user))).isEmpty();
        assertThat(repository.getUserHistory(user, DOCUMENT, 0, 10, "modification_date",true, "prj")).isEmpty();
        assertThat(repository.getUserEvents(user)).isEmpty();
    }

    @Test
    public void test_save_project() {
        Project project = new Project(
                "projectId",
                "Project",
                Path.of("/vault/project"),
                "https://icij.org",
                "Data Team",
                "ICIJ",
                null,
                "*",
                new Date(),
                new Date());
        repository.save(project);
        assertThat(repository.getProject("projectId").getLabel()).isEqualTo("Project");
    }
    @Test
    public void test_save_project_with_description() {
        Project project = new Project(
                "anotherProjectId",
                "Another Project",
                "This is a another project",
                Path.of("/vault/project"),
                "https://icij.org",
                "Data Team",
                "ICIJ",
                null,
                "*",
                new Date(),
                new Date());
        repository.save(project);
        assertThat(repository.getProject("anotherProjectId").getDescription()).isEqualTo("This is a another project");
    }

    @Test
    public void test_save_existing_project() {
        repository.save(new Project(
                "projectId",
                "Project ID",
                Path.of("/vault/project"),
                "https://icij.org",
                "Data Team",
                "ICIJ",
                null,
                "*",
                new Date(),
                new Date()));
        // Save the same project twice
        repository.save(new Project(
                "projectId",
                "Project ID v2",
                Path.of("/vault/project/foo"),
                "https://icij.org/foo",
                "Tech Team",
                "ICIJ",
                null,
                "*/foo",
                new Date(),
                new Date()));
        Project updatedProject = repository.getProject("projectId");
        assertThat(updatedProject.getLabel()).isEqualTo("Project ID v2");
        assertThat(updatedProject.getMaintainerName()).isEqualTo("Tech Team");
        assertThat(updatedProject.getSourceUrl()).isEqualTo("https://icij.org/foo");
        // Only this value should be unchanged
        assertThat(updatedProject.getSourcePath().toString()).isEqualTo("/vault/project");
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
        assertThat(repository.getUserHistory(User.local(), SEARCH, 0, 10, "modification_date", true)).isEmpty();
    }

    @Test
    public void test_sort_user_history_with_default_field(){
        Date date1 = new Date(new Date().getTime());
        Date date2 = new Date(date1.getTime() + 100);
        Date date3 = new Date(date1.getTime() + 200);
        List<Project> project = singletonList(project("project"));

        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri(), date1, date1);
        UserEvent userEvent2 = new UserEvent(User.local(), DOCUMENT, "doc_name2", Paths.get("uri2_doc").toUri(), date2, date2);
        UserEvent userEvent3 = new UserEvent(User.local(), DOCUMENT, "doc_name3", Paths.get("doc_uri3").toUri(), date3, date3);

        repository.addToUserHistory(project, userEvent3);
        repository.addToUserHistory(project, userEvent);
        repository.addToUserHistory(project, userEvent2);

        List<UserEvent> userEvents = repository.getUserHistory(User.local(), DOCUMENT, 0, 10,null ,true);
        assertThat(userEvents).hasSize(3);
        assertThat(userEvents.get(0).modificationDate.getTime()).isEqualTo(date3.getTime());
        assertThat(userEvents.get(1).modificationDate.getTime()).isEqualTo(date2.getTime());
        assertThat(userEvents.get(2).modificationDate.getTime()).isEqualTo(date1.getTime());

        List<UserEvent> userEventsAsc = repository.getUserHistory(User.local(), DOCUMENT, 0, 10,null ,false);
        assertThat(userEventsAsc.get(0).modificationDate.getTime()).isEqualTo(date1.getTime());
        assertThat(userEventsAsc.get(1).modificationDate.getTime()).isEqualTo(date2.getTime());
        assertThat(userEventsAsc.get(2).modificationDate.getTime()).isEqualTo(date3.getTime());
    }

    @Test
    public void test_sort_user_history_with_valid_field(){
        Date date1 = new Date(new Date().getTime());
        List<Project> project = singletonList(project("project"));

        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri(), date1, date1);
        UserEvent userEvent2 = new UserEvent(User.local(), DOCUMENT, "doc_name2", Paths.get("uri2_doc").toUri(), date1, date1);
        UserEvent userEvent3 = new UserEvent(User.local(), DOCUMENT, "doc_name3", Paths.get("doc_uri3").toUri(), date1, date1);

        repository.addToUserHistory(project, userEvent3);
        repository.addToUserHistory(project, userEvent);
        repository.addToUserHistory(project, userEvent2);

        List<UserEvent> userEvents = repository.getUserHistory(User.local(), DOCUMENT, 0, 10, USER_HISTORY.URI.getName() ,true);
        assertThat(userEvents).hasSize(3);
        assertThat(userEvents.get(0).uri.getPath()).contains("uri2_doc");
        assertThat(userEvents.get(1).uri.getPath()).contains("doc_uri3");
        assertThat(userEvents.get(2).uri.getPath()).contains("doc_uri1");

        List<UserEvent> userEventsAsc = repository.getUserHistory(User.local(), DOCUMENT, 0, 10,USER_HISTORY.URI.getName() ,false);
        assertThat(userEventsAsc.get(0).uri.getPath()).contains("doc_uri1");
        assertThat(userEventsAsc.get(1).uri.getPath()).contains("doc_uri3");
        assertThat(userEventsAsc.get(2).uri.getPath()).contains("uri2_doc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_sort_user_history_with_wrong_value(){
        repository.getUserHistory(User.local(), SEARCH, 0, 10, "modificationDate", true);
    }

    @Test
    public void test_sort_user_history_with_project_filter(){
        Date date1 = new Date(new Date().getTime());
        List<Project> project = singletonList(project("project"));
        List<Project> project2 = asList(project("project"),project("project2"));
        List<Project> project1 = singletonList(project("project1"));

        repository.addToUserHistory(project, new UserEvent(User.local(), DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri(), date1, date1));
        repository.addToUserHistory(project2, new UserEvent(User.local(), DOCUMENT, "doc_name2", Paths.get("uri2_doc").toUri(), date1, date1));
        repository.addToUserHistory(project1, new UserEvent(User.local(), DOCUMENT, "doc_name3", Paths.get("doc_uri3").toUri(), date1, date1));

        assertThat(repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true, "project")).hasSize(2);
        assertThat(repository.getUserHistorySize(User.local(), DOCUMENT, "project")).isEqualTo(2);
        assertThat(repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true, "project3", "project2")).hasSize(1);
        assertThat(repository.getUserHistorySize(User.local(), DOCUMENT,"project3", "project2")).isEqualTo(1);

        assertThat(repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true, "")).hasSize(0);
        assertThat(repository.getUserHistorySize(User.local(), DOCUMENT, "")).isEqualTo(0);

        assertThat(repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true )).hasSize(3);
        assertThat(repository.getUserHistorySize(User.local(), DOCUMENT)).isEqualTo(3);
    }

    @Test
    public void test_add_get_document_to_user_history() {
        User user = new User("userid");
        User user2 = new User("userid2");
        Date date1 = new Date(new Date().getTime());
        Date date2 = new Date(new Date().getTime() + 100);

        Path docUri1 = Paths.get("doc_uri1");
        UserEvent userEvent = new UserEvent(user, DOCUMENT, "doc_name1", docUri1.toUri(), date1, date1);
        UserEvent userEvent2 = new UserEvent(user, DOCUMENT, "doc_name2", Paths.get("doc_uri2").toUri(), date2, date2);
        UserEvent userEvent3 = new UserEvent(user2, DOCUMENT, "doc_name1", docUri1.toUri());

        assertThat(repository.addToUserHistory(asList(project("project1"),project("project2")), userEvent)).isTrue();
        assertThat(repository.addToUserHistory(singletonList(project("project")), userEvent2)).isTrue();
        assertThat(repository.addToUserHistory(singletonList(project("project")), userEvent3)).isTrue();
        assertThat(repository.getUserHistory(user, DOCUMENT, 0, 10, "modification_date", true)).containsSequence(userEvent2,userEvent);
        assertThat(repository.getUserHistorySize(user, DOCUMENT)).isEqualTo(2);
        assertThat(repository.getUserHistory(user, DOCUMENT, 0, 1, "modification_date", true)).containsExactly(userEvent2);
        assertThat(repository.getUserHistory(user2, DOCUMENT, 0, 10, "modification_date", true)).containsExactly(userEvent3);
        assertThat(repository.getUserHistorySize(user2, DOCUMENT)).isEqualTo(1);
        assertThat(repository.getUserHistory(user, SEARCH, 0, 10, "modification_date", true)).isEmpty();
    }

    @Test
    public void test_update_user_event() {
        Date date1 = new Date(new Date().getTime());
        Date date2 = new Date(new Date().getTime() + 100);
        Path docUri = Paths.get("doc_uri");
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", 
                docUri.toUri(), date1, date1);
        UserEvent userEvent2 = new UserEvent(User.local(), DOCUMENT, "doc_name", docUri.toUri(), date2, date2);

        assertThat(repository.addToUserHistory(singletonList(project("project")), userEvent)).isTrue();
        assertThat(repository.addToUserHistory(singletonList(project("project")), userEvent2)).isTrue();

        assertThat(repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true)).containsExactly(userEvent);
    }

    @Test
    public void test_delete_user_event_by_type() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", Paths.get("doc_uri").toUri());
        UserEvent userEvent2 = new UserEvent(User.local(), SEARCH, "search_name", Paths.get("search_uri").toUri());
        repository.addToUserHistory(singletonList(project("project")), userEvent);
        repository.addToUserHistory(singletonList(project("project")), userEvent2);

        assertThat(repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true)).containsExactly(userEvent);
        assertThat(repository.deleteUserHistory(User.local(), DOCUMENT)).isTrue();
        assertThat(repository.deleteUserHistory(User.local(), DOCUMENT)).isFalse();
        assertThat(repository.getUserHistorySize(User.local(),DOCUMENT)).isEqualTo(0);
        assertThat(repository.getUserHistory(User.local(),SEARCH, 0, 10, "modification_date", true)).containsExactly(userEvent2);
    }

    @Test
    public void test_delete_single_user_event_by_id() {
        repository.addToUserHistory(singletonList(project("project")), new UserEvent(User.local(), DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri()));
        repository.addToUserHistory(singletonList(project("project")), new UserEvent(User.local(), DOCUMENT, "doc_name2", Paths.get("doc_uri2").toUri()));
        List<UserEvent> userEvents = repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true);

        assertThat(repository.deleteUserHistoryEvent(User.local(), userEvents.get(1).id)).isTrue();
        assertThat(repository.deleteUserHistoryEvent(User.local(), userEvents.get(1).id)).isFalse();
        assertThat(repository.getUserHistory(User.local(),DOCUMENT, 0, 10, "modification_date", true)).containsExactly(userEvents.get(0));
    }

    @Test
    public void test_rename_single_user_event_by_id() {
        repository.addToUserHistory(singletonList(project("project")), new UserEvent(User.local(), SEARCH, "search1", Paths.get("search_uri").toUri()));
        List<UserEvent> userSearchEvents = repository.getUserHistory(User.local(), SEARCH, 0, 10, "modification_date", true);
        assertThat(userSearchEvents.get(0).name).isEqualTo("search1");
        boolean renamed =  repository.renameSavedSearch(User.local(),userSearchEvents.get(0).id, "search_renamed");
        assertThat(renamed).isTrue();
        List<UserEvent> userSearchEvents2 = repository.getUserHistory(User.local(), SEARCH, 0, 10, "modification_date", true);
        assertThat(userSearchEvents2.get(0).name).isEqualTo("search_renamed");

        repository.addToUserHistory(singletonList(project("project")), new UserEvent(User.local(), DOCUMENT, "doc_name1", Paths.get("doc_uri1").toUri()));
        List<UserEvent> userDocEvents = repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true);
        assertThat(userDocEvents.get(0).name).isEqualTo("doc_name1");
        boolean renamedDoc =  repository.renameSavedSearch(User.local(),userDocEvents.get(0).id, "search_renamed");
        assertThat(renamedDoc).isFalse();
        List<UserEvent> userDocEvents2 = repository.getUserHistory(User.local(), DOCUMENT, 0, 10, "modification_date", true);
        assertThat(userDocEvents2.get(0).name).isEqualTo("doc_name1");
    }
    @Test
    public void test_db_status(){
        assertThat(repository.getHealth()).isTrue();
    }
}
