package org.icij.datashare.project.admin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Singleton
public class ProjectAdminServiceImpl implements ProjectAdminService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAdminServiceImpl.class);
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
    private static final Pattern ALLOW_FROM_MASK = Pattern.compile("^[\\d*]{1,3}(\\.[\\d*]{1,3}){3}$");
    private static final String DEFAULT_ALLOW_FROM_MASK = "*.*.*.*";
    private static final Path DEFAULT_VAULT = Paths.get("/vault");

    private final Repository repository;
    private final Indexer indexer;
    private final Authorizer authorizer;
    private final DocumentCollectionFactory<Path> documentCollectionFactory;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public ProjectAdminServiceImpl(Repository repository,
                                   Indexer indexer,
                                   Authorizer authorizer,
                                   DocumentCollectionFactory<Path> documentCollectionFactory,
                                   PropertiesProvider propertiesProvider) {
        this.repository = repository;
        this.indexer = indexer;
        this.authorizer = authorizer;
        this.documentCollectionFactory = documentCollectionFactory;
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public ProjectCreated create(ProjectCreateRequest request)
            throws ProjectExistsException, ValidationException, IOException {
        validate(request);
        if (repository.getProject(request.name()) != null) {
            throw new ProjectExistsException(request.name());
        }
        return persist(request);
    }

    @Override
    public ProjectCreated createIfNotExists(ProjectCreateRequest request)
            throws ValidationException, IOException {
        validate(request);
        Project existing = repository.getProject(request.name());
        if (existing != null) {
            return new ProjectCreated(
                    existing.getName(),
                    existing.getLabel(),
                    existing.getDescription(),
                    existing.getSourcePath(),
                    existing.getAllowFromMask(),
                    existing.getSourceUrl(),
                    existing.getMaintainerName(),
                    existing.getPublisherName(),
                    existing.getLogoUrl(),
                    existing.creationDate,
                    existing.updateDate,
                    false,
                    true);
        }
        return persist(request);
    }

    @Override
    public ProjectStats stats(String name, boolean includeIndexCount) throws ProjectNotFoundException, IOException {
        if (repository.getProject(name) == null) {
            throw new ProjectNotFoundException(name);
        }
        long indexedDocuments = includeIndexCount
                ? indexer.count(name)
                : ProjectStats.INDEX_CHECK_SKIPPED;
        int memberCount = (int) authorizer
                .getGroupPermissions(Domain.of("datashare"), name)
                .stream()
                .map(CasbinRule::getV0)
                .distinct()
                .count();
        return new ProjectStats(name, indexedDocuments, memberCount);
    }

    @Override
    public ProjectDeleted delete(String name, ProjectDeleteOptions options)
            throws ProjectNotFoundException, IOException {
        Project project = repository.getProject(name);
        if (project == null) {
            throw new ProjectNotFoundException(name);
        }
        return cascade(project, options);
    }

    @Override
    public ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException {
        Project project = repository.getProject(name);
        if (project == null) {
            return new ProjectDeleted(name, false, false, false, false, false, true);
        }
        return cascade(project, options);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean addAdminToProject(String projectName, String userLogin)
            throws ProjectNotFoundException {
        Project project = repository.getProject(projectName);
        if (project == null) {
            throw new ProjectNotFoundException(projectName);
        }
        User user = repository.getUser(userLogin);
        if (user == null) {
            // No row in user_inventory: cannot update the per-user project list.
            // Caller (CLI dispatcher) may log a warning and continue.
            return false;
        }

        // 1. Append projectName to user.details["groups_by_applications.datashare"]
        //    via a fresh details map (User.details is unmodifiable).
        Map<String, Object> newDetails = new HashMap<>(user.details);
        Object appsRaw = newDetails.get("groups_by_applications");
        Map<String, Object> apps = appsRaw instanceof Map<?, ?>
                ? new LinkedHashMap<>((Map<String, Object>) appsRaw)
                : new LinkedHashMap<>();
        Object datashareRaw = apps.get("datashare");
        List<String> currentProjects = datashareRaw instanceof List<?>
                ? new ArrayList<>((List<String>) datashareRaw)
                : new ArrayList<>();
        if (!currentProjects.contains(projectName)) {
            currentProjects.add(projectName);
        }
        apps.put("datashare", currentProjects);
        newDetails.put("groups_by_applications", apps);

        User updated = new User(user.id, user.name, user.email, user.provider, newDetails);
        repository.save(updated);

        // 2. Casbin grouping policy: g <user.id> PROJECT_ADMIN datashare::<projectName>
        authorizer.addProjectAdmin(updated, Domain.of("datashare"), project);

        return true;
    }

    private ProjectDeleted cascade(Project project, ProjectDeleteOptions options) throws IOException {
        String name = project.getName();

        boolean indexDeleted = false;
        if (!options.keepIndex()) {
            indexDeleted = indexer.deleteAll(name);
        }

        boolean dbDeleted = repository.deleteAll(name);

        boolean queuesDeleted = deleteQueues(project);
        boolean reportMapDeleted = deleteReportMap(project);
        boolean artifactsDeleted = deleteArtifacts(name);

        return new ProjectDeleted(name, dbDeleted, indexDeleted,
                queuesDeleted, reportMapDeleted, artifactsDeleted, false);
    }

    private boolean deleteQueues(Project project) {
        String name = project.getName();
        Properties properties = propertiesProvider.createOverriddenWith(
                Map.of(PropertiesProvider.DEFAULT_PROJECT_OPT, name));
        String defaultQueueName = properties.getOrDefault(
                PropertiesProvider.QUEUE_NAME_OPT, "extract:queue").toString();
        String queuePrefix = defaultQueueName + PropertiesProvider.QUEUE_SEPARATOR + name;
        String queuePattern = queuePrefix + PropertiesProvider.QUEUE_SEPARATOR + "*";
        return Stream.concat(
                        documentCollectionFactory.getQueues(queuePrefix, Path.class).stream(),
                        documentCollectionFactory.getQueues(queuePattern, Path.class).stream())
                .allMatch(DocumentQueue::delete);
    }

    private boolean deleteReportMap(Project project) {
        String reportMapName = "extract:report:" + project.getName();
        return documentCollectionFactory.createMap(reportMapName).delete();
    }

    private boolean deleteArtifacts(String name) {
        return propertiesProvider.get(DatashareCliOptions.ARTIFACT_DIR_OPT)
                .map(dir -> {
                    try {
                        File projectArtifactDir = Path.of(dir).resolve(name).toFile();
                        FileUtils.deleteDirectory(projectArtifactDir);
                        return true;
                    } catch (IOException e) {
                        LOGGER.error("cannot delete project {} artifact dir", name, e);
                        return false;
                    }
                })
                .orElse(false);
    }

    private void validate(ProjectCreateRequest request) throws ValidationException {
        if (request.name() == null || !NAME.matcher(request.name()).matches()) {
            throw new ValidationException("name",
                    "project name must match ^[a-z0-9][a-z0-9-]{1,63}$");
        }
        if (request.allowFromMask() != null
                && !ALLOW_FROM_MASK.matcher(request.allowFromMask()).matches()) {
            throw new ValidationException("allowFromMask",
                    "allow-from-mask must match ^[\\d*]{1,3}(\\.[\\d*]{1,3}){3}$");
        }
        if (request.sourceUrl() != null) {
            validateUri(request.sourceUrl(), "sourceUrl");
        }
        if (request.logoUrl() != null) {
            validateUri(request.logoUrl(), "logoUrl");
        }
    }

    private static void validateUri(String value, String field) throws ValidationException {
        if (value.isBlank()) {
            throw new ValidationException(field, field + " must not be blank");
        }
        try {
            java.net.URI parsed = java.net.URI.create(value);
            if (parsed.getScheme() == null) {
                throw new ValidationException(field, field + " must include a scheme (e.g. https://)");
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field, field + " is not a valid RFC 3986 URI: " + e.getMessage());
        }
    }

    private ProjectCreated persist(ProjectCreateRequest request) throws IOException {
        String label = request.label() == null ? request.name() : request.label();
        Path sourcePath = request.sourcePath() == null
                ? DEFAULT_VAULT.resolve(request.name())
                : request.sourcePath();
        String allowFromMask = request.allowFromMask() == null
                ? DEFAULT_ALLOW_FROM_MASK
                : request.allowFromMask();
        // Auto-stamp on create. Backfill operators can override via explicit
        // request.creationDate()/updateDate(); otherwise both fields share the
        // same "now" timestamp (typical fresh-row pattern: a freshly created
        // project hasn't been updated yet, so creation == update).
        Date creationDate = request.creationDate() == null ? new Date() : request.creationDate();
        Date updateDate = request.updateDate() == null ? creationDate : request.updateDate();

        Project project = new Project(
                request.name(),
                label,
                request.description(),
                sourcePath,
                request.sourceUrl(),
                request.maintainerName(),
                request.publisherName(),
                request.logoUrl(),
                allowFromMask,
                creationDate,
                updateDate
        );

        if (!repository.save(project)) {
            throw new IllegalStateException("repository.save(Project) returned false for " + request.name());
        }

        boolean indexCreated = false;
        if (request.createIndex()) {
            try {
                indexer.createIndex(request.name());
                indexCreated = true;
            } catch (RuntimeException | IOException e) {
                // Compensating delete: a DB row pointing at a missing index
                // is the worst end-state. Roll back, then rethrow.
                try {
                    repository.deleteAll(request.name());
                } catch (RuntimeException rollback) {
                    e.addSuppressed(rollback);
                }
                if (e instanceof IOException io) throw io;
                throw (RuntimeException) e;
            }
        }

        return new ProjectCreated(
                project.getName(),
                project.getLabel(),
                project.getDescription(),
                project.getSourcePath(),
                project.getAllowFromMask(),
                project.getSourceUrl(),
                project.getMaintainerName(),
                project.getPublisherName(),
                project.getLogoUrl(),
                creationDate,
                updateDate,
                indexCreated,
                false);
    }
}
