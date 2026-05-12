package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.cli.CliExtensionService;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.ArtifactTask;
import org.icij.datashare.tasks.CreateNlpBatchesFromIndex;
import org.icij.datashare.tasks.CategorizeTask;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.tasks.DeduplicateTask;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.admin.UserAdminService;
import org.icij.datashare.user.admin.UserCreateRequest;
import org.icij.datashare.user.admin.UserCreated;
import org.icij.datashare.user.admin.UserExistsException;
import org.icij.datashare.user.admin.UserNotFoundException;
import org.icij.datashare.user.admin.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.propertiesToMap;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.user.User.localUser;
import static org.icij.datashare.user.User.nullUser;

class CliApp {
    private static final Logger logger = LoggerFactory.getLogger(CliApp.class);

    static void start(Properties properties) throws Exception {
        ExtensionService extensionService = new ExtensionService(new PropertiesProvider(properties));
        process(extensionService, properties);
        process(new PluginService(new PropertiesProvider(properties), extensionService), properties);
        try (CommonMode commonMode = CommonMode.create(properties)) {
            List<CliExtension> extensions = CliExtensionService.getInstance().getExtensions();

            logger.info("found {} CLI extension(s)", extensions.size());
            if (extensions.size() == 1 && extensions.get(0).identifier().equals(properties.get("ext"))) {
                CliExtension extension = extensions.get(0);
                extension.init(commonMode::createChildInjector);
                extension.run(properties);
                System.exit(0);
            }
            runTaskWorker(commonMode, properties);
        }
    }

    private static void process(DeliverableService<?> deliverableService, Properties properties) throws IOException {
        String listPattern = deliverableService.getListOpt(properties);
        if (listPattern != null) {
            listPattern = listPattern.equalsIgnoreCase("true") ? ".*":listPattern;
            deliverableService.list(listPattern).forEach(DeliverablePackage::displayInformation);
        } else if(deliverableService.getInstallOpt(properties) != null) {
            deliverableService.downloadAndInstallFromCli(properties);
        } else if (deliverableService.getDeleteOpt(properties) != null) {
            deliverableService.deleteFromCli(properties);
        } else return;
        System.exit(0);
    }

    private static void runTaskWorker(CommonMode mode, Properties properties) throws Exception {
        TaskManager taskManager = mode.get(TaskManager.class);
        DatashareTaskFactory taskFactory = mode.get(DatashareTaskFactory.class);
        Indexer indexer = mode.get(Indexer.class);

        if (properties.getProperty(CREATE_INDEX_OPT) != null) {
            indexer.createIndex(properties.getProperty(CREATE_INDEX_OPT));
            System.exit(0);
        }

        if (properties.getProperty(CRE_API_KEY_OPT) != null) {
            String userName = properties.getProperty(CRE_API_KEY_OPT);
            String secretKey = taskFactory.createGenApiKey(localUser(userName)).call();
            logger.info("generated secret key for user {} (store it somewhere safe, datashare cannot retrieve it later): {}", userName, secretKey);
            System.exit(0);
        }

        if (properties.getProperty(GET_API_KEY_OPT) != null) {
            String userName = properties.getProperty(GET_API_KEY_OPT);
            String hashedKey = taskFactory.createGetApiKey(localUser(userName)).call();
            if ((hashedKey == null)) {
                logger.info("no user {} exists", userName);
            } else {
                logger.info("hashed key for user {} is {}", userName, hashedKey);
            }
            System.exit(0);
        }

        if (properties.getProperty(DEL_API_KEY_OPT) != null) {
            String userName = properties.getProperty(DEL_API_KEY_OPT);
            taskFactory.createDelApiKey(localUser(userName)).call();
            System.exit(0);
        }

        if (properties.getProperty(GRANT_ADMIN_OPT) != null) {
            String userName = properties.getProperty(GRANT_ADMIN_OPT);
            taskFactory.createGrantAdminPolicyTask(localUser(userName)).call();
            System.exit(0);
        }

        if (properties.getProperty(USER_CREATE_OPT) != null) {
            UserAdminService userAdminService = mode.get(UserAdminService.class);
            System.exit(handleUserCreate(userAdminService, properties));
        }

        if (properties.getProperty(USER_DELETE_OPT) != null) {
            UserAdminService userAdminService = mode.get(UserAdminService.class);
            System.exit(handleUserDelete(userAdminService, properties));
        }

        PipelineHelper pipeline = new PipelineHelper(new PropertiesProvider(properties));
        logger.info("executing {}", pipeline);
        if (pipeline.has(Stage.DEDUPLICATE)) {
            taskManager.startTask(DeduplicateTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.SCANIDX)) {
            taskManager.startTask(ScanIndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.SCAN)) {
            taskManager.startTask(ScanTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.INDEX)) {
            taskManager.startTask(IndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.ENQUEUEIDX)) {
            taskManager.startTask(EnqueueFromIndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.CATEGORIZE)) {
            taskManager.startTask(CategorizeTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.CREATENLPBATCHESFROMIDX)) {
            taskManager.startTask(CreateNlpBatchesFromIndex.class.getName(), nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.NLP)) {
            taskManager.startTask(ExtractNlpTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.ARTIFACT)) {
            taskManager.startTask(ArtifactTask.class, nullUser(), propertiesToMap(properties));
        }
        taskManager.awaitTermination(Integer.MAX_VALUE, SECONDS);
        taskManager.shutdown();
    }

    // Single ObjectMapper shared across handlers for serializing JSON output.
    // Input is read from typed sibling properties, not parsed JSON, so this
    // mapper never sees untrusted strings.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static int handleUserCreate(UserAdminService service, Properties properties) {
        String login = properties.getProperty(USER_CREATE_OPT);
        boolean json = Boolean.parseBoolean(properties.getProperty(USER_CREATE_JSON_OPT));
        boolean ifNotExists = Boolean.parseBoolean(properties.getProperty(USER_CREATE_IF_NOT_EXISTS_OPT));
        try {
            UserCreateRequest request = new UserCreateRequest(
                    login,
                    properties.getProperty(USER_CREATE_EMAIL_OPT),
                    properties.getProperty(USER_CREATE_NAME_OPT),
                    properties.getProperty(USER_CREATE_PASSWORD_OPT),
                    properties.getProperty(USER_CREATE_PROVIDER_OPT),
                    Validators.groups(properties.getProperty(USER_CREATE_GROUPS_OPT)));

            UserCreated created = ifNotExists ? service.createIfNotExists(request) : service.create(request);

            if (json) {
                System.out.println(MAPPER.writeValueAsString(Map.of(
                        "created", !created.noop(),
                        "noop", created.noop(),
                        "login", created.login(),
                        "email", created.email(),
                        "name", created.name(),
                        "provider", created.provider(),
                        "groups", created.groups())));
            } else if (created.noop()) {
                System.out.println("user '" + created.login() + "' already exists (no-op)");
            } else {
                System.out.println("created user '" + created.login() + "' (provider="
                        + created.provider() + ", groups=" + created.groups() + ")");
            }
            return 0;
        } catch (UserExistsException e) {
            return error(e.getMessage(), "conflict", 4, json);
        } catch (ValidationException e) {
            return error(e.getMessage(), "validation", 5, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", 1, json);
        } finally {
            // Drop the password from the in-memory Properties as soon as the
            // request has been built, even on the error path.
            properties.remove(USER_CREATE_PASSWORD_OPT);
        }
    }

    static int handleUserDelete(UserAdminService service, Properties properties) {
        // Confirmation is handled in UserDeleteCommand before dispatch; this
        // path only runs when the user has already agreed (or passed --yes).
        String login = properties.getProperty(USER_DELETE_OPT);
        boolean json = Boolean.parseBoolean(properties.getProperty(USER_DELETE_JSON_OPT));
        boolean ifExists = Boolean.parseBoolean(properties.getProperty(USER_DELETE_IF_EXISTS_OPT));
        try {
            boolean removed = ifExists ? service.deleteIfExists(login) : service.delete(login);
            boolean noop = !removed;

            if (json) {
                System.out.println(MAPPER.writeValueAsString(Map.of(
                        "deleted", removed,
                        "noop", noop,
                        "login", login)));
            } else if (noop) {
                System.out.println("user '" + login + "' does not exist (no-op)");
            } else {
                System.out.println("deleted user '" + login + "'");
            }
            return 0;
        } catch (UserNotFoundException e) {
            return error(e.getMessage(), "not_found", 3, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", 1, json);
        }
    }

    private static int error(String message, String code, int exit, boolean json) {
        if (json) {
            try {
                System.err.println(MAPPER.writeValueAsString(Map.of(
                        "error", code, "message", message)));
            } catch (Exception e) {
                System.err.println("error: " + message);
            }
        } else {
            System.err.println("error: " + message);
        }
        return exit;
    }
}
