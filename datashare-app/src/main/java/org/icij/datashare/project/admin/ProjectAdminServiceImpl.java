package org.icij.datashare.project.admin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

@Singleton
public class ProjectAdminServiceImpl implements ProjectAdminService {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
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
                    false,
                    true);
        }
        return persist(request);
    }

    @Override
    public ProjectStats stats(String name) throws ProjectNotFoundException, IOException {
        throw new UnsupportedOperationException("implemented in Task 8");
    }

    @Override
    public ProjectDeleted delete(String name, ProjectDeleteOptions options)
            throws ProjectNotFoundException, IOException {
        throw new UnsupportedOperationException("implemented in Task 9");
    }

    @Override
    public ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException {
        throw new UnsupportedOperationException("implemented in Task 9");
    }

    private void validate(ProjectCreateRequest request) throws ValidationException {
        if (request.name() == null || !NAME.matcher(request.name()).matches()) {
            throw new ValidationException("name",
                    "project name must match ^[a-z0-9][a-z0-9-]{1,63}$");
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
                null,  // creationDate (null matches REST POST behavior)
                null   // updateDate
        );

        if (!repository.save(project)) {
            throw new IOException("repository.save(Project) returned false for " + request.name());
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
                indexCreated,
                false);
    }
}
