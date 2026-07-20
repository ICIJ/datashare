package org.icij.datashare.mode;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.inject.*;
import com.google.inject.Module;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.codestory.http.Configuration;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.injection.GuiceAdapter;
import net.codestory.http.misc.Env;
import net.codestory.http.routes.Routes;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.asyncsearch.AsyncSearchStore;
import org.icij.datashare.asyncsearch.MemoryAsyncSearchStore;
import org.icij.datashare.asyncsearch.RedisAsyncSearchStore;
import org.icij.datashare.asynctasks.*;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.cli.TaskRepositoryType;
import org.icij.datashare.com.queue.AmqpInterlocutor;
import org.icij.datashare.db.JooqTaskRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.extension.ExtensionLoader;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.extract.RedisDocumentCollectionFactory;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.nlp.OptimaizeLanguageGuesser;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.PolicyWatcher;
import org.icij.datashare.session.UsersInRedis;
import org.icij.datashare.session.UserStore;
import org.icij.datashare.session.YesCookieAuthFilter;
import org.icij.datashare.user.admin.UserAdminService;
import org.icij.datashare.user.admin.UserAdminServiceImpl;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectAdminServiceImpl;
import org.icij.datashare.session.StatusCidrFilter;
import org.icij.datashare.cli.AuthMode;
import org.icij.datashare.cli.AuthUsersProvider;
import org.icij.datashare.session.UsersInDb;
import net.codestory.http.security.Users;
import org.icij.datashare.session.UsersIdProviderCache;
import org.icij.datashare.session.UsersIdProviderRedisCache;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.TaskResultSubtypes;
import org.icij.datashare.tasks.Utils;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.web.OpenApiResource;
import org.icij.datashare.web.RootResource;
import org.icij.datashare.web.SettingsResource;
import org.icij.datashare.web.StatusResource;
import org.icij.extract.redis.CloseableRedissonClient;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.io.Serializable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.PluginService.PLUGINS_BASE_URL;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.cli.QueueType.TEMPORAL;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public abstract class CommonMode extends AbstractModule implements Closeable {
    protected Logger logger = LoggerFactory.getLogger(getClass());

    protected final PropertiesProvider propertiesProvider;
    protected final Mode mode;
    private final Injector injector;
    private final Deque<Closeable> closeables = new ArrayDeque<>();
    private final ExecutorService executorService;

    protected CommonMode(Properties properties) {
        propertiesProvider = properties == null ? new PropertiesProvider() :
                new PropertiesProvider(properties.getProperty(PropertiesProvider.SETTINGS_OPT)).overrideWith(properties);
        this.mode = getMode(properties);

        JsonObjectMapper.registerSubtypes(TaskResultSubtypes.getClasses());

        // Eager load extension JARs before Guice injector creation
        String extensionsDir = getExtensionsDir();
        if (extensionsDir != null) {
            try {
                new ExtensionLoader(Paths.get(extensionsDir)).eagerLoadJars();
            } catch (FileNotFoundException e) {
                logger.warn("Failed to eager load extensions: {}", e.getMessage());
            }
        }
        executorService = Executors.newFixedThreadPool(Math.max(getTaskWorkersNb(), 1));
        try {
            this.injector = Guice.createInjector(this);
        } catch (CreationException e) {
            logger.error("cannot create injector: {}", e.getErrorMessages());
            logger.error("exception: ", e);
            throw e;
        }
    }

    CommonMode(final Map<String, Object> map) {
        this(PropertiesProvider.fromMap(map));
    }

    public static CommonMode create(final Map<String, Object> map) {
        return create(PropertiesProvider.fromMap(map));
    }

    public static CommonMode create(final Map<String, Object> map, boolean override) {
        return create(PropertiesProvider.fromMap(map));
    }
    public static CommonMode create(final Properties properties) {
        return switch (getMode(properties)) {
            case NER -> new NerMode(properties);
            case LOCAL -> new LocalMode(properties);
            case EMBEDDED -> new EmbeddedMode(properties);
            case SERVER -> new ServerMode(properties);
            case TASK_WORKER, CLI -> new CliMode(properties);
        };
    }

    public Mode getMode() {return mode;}
    public <T> T get(Class<T> type) {return injector.getInstance(type);}
    public <T> T get(Key<T> key) {return injector.getInstance(key);}
    public Injector createChildInjector(Module... modules) {
        return injector.createChildInjector(modules);
    }
    public boolean shouldRunWorker() {
        boolean batchQueueType = getQueueType(propertiesProvider, BATCH_QUEUE_TYPE_OPT, DEFAULT_BATCH_QUEUE_TYPE)
            .equals(TEMPORAL);
        return (CommonMode.getMode(this.properties()) == Mode.EMBEDDED || getMode() == Mode.LOCAL)
                && (properties().containsValue(QueueType.AMQP.name()) || batchQueueType);
    }
    int getTaskWorkersNb() {
        return parseInt((String) ofNullable(properties().get(TASK_WORKERS_OPT)).orElse(DEFAULT_TASK_WORKERS));
    }
    double getProgressMinIntervalS() {
        return ofNullable(properties().getProperty(TASK_PROGRESS_INTERVAL_OPT))
                .map(Double::parseDouble)
                .orElse(DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS);
    }

    public ExecutorService runWorkers() {
        QueueType batchQueueType = getQueueType(propertiesProvider, BATCH_QUEUE_TYPE_OPT, DEFAULT_BATCH_QUEUE_TYPE);
        if (batchQueueType.equals(TEMPORAL)) {
            return runTemporalWorkers();
        }
        return runTaskWorkerLoop();
    }

    @Override
    protected void configure() {
        bind(PropertiesProvider.class).toInstance(propertiesProvider);
        bind(StatusCidrFilter.class).asEagerSingleton();
        install(new FactoryModuleBuilder().build(DatashareTaskFactory.class));

        QueueType batchQueueType = getQueueType(propertiesProvider, BATCH_QUEUE_TYPE_OPT, DEFAULT_BATCH_QUEUE_TYPE);
        switch ( batchQueueType ) {
            case REDIS:
                bind(TaskManager.class).to(TaskManagerRedis.class);
                bind(TaskModifier.class).to(TaskSupplierRedis.class);
                bind(TaskSupplier.class).to(TaskSupplierRedis.class);
                break;
            case AMQP:
                bind(TaskManager.class).to(TaskManagerAmqp.class);
                bind(TaskSupplier.class).to(TaskSupplierAmqp.class);
                bind(TaskModifier.class).to(TaskSupplierAmqp.class);
                break;
            case TEMPORAL:
                bind(TaskManager.class).to(TaskManagerTemporal.class);
                break;
            default:
                bind(TaskManager.class).to(TaskManagerMemory.class);
                bind(TaskModifier.class).to(TaskManagerMemory.class);
                bind(TaskSupplier.class).to(TaskManagerMemory.class);
        }
    }

    @Provides @Singleton
    RedissonClient provideRedissonClient() {
        int poolSize = redisPoolSize(propertiesProvider);
        propertiesProvider.get(REDIS_POOL_SIZE_OPT).map(Integer::parseInt)
                .filter(configured -> configured < poolSize)
                .ifPresent(configured -> logger.warn("redisPoolSize {} is below the {} connections needed for the worker pool; raising it to {}", configured, poolSize, poolSize));
        logger.info("sizing the main Redis connection pool to {}", poolSize);
        Properties redisProperties = propertiesProvider.createOverriddenWith(Map.of(REDIS_POOL_SIZE_OPT, Integer.toString(poolSize)));
        CloseableRedissonClient redissonClient = new RedissonClientFactory().withOptions(Options.from(redisProperties)).createCloseable();
        addCloseable(redissonClient);
        return redissonClient;
    }

    /**
     * Effective size of the shared Redis connection pool. Blocking BLPOP workers each hold one
     * connection for the whole poll timeout, so the pool must have at least one connection per
     * worker plus headroom for the task supplier and short-lived commands. Returns
     * {@code parallelism + REDIS_POOL_SIZE_OVERHEAD}, raising any explicit redisPoolSize up to that
     * floor. Parallelism falls back to DEFAULT_PARALLELISM (the largest worker count a stage may
     * use) when unset, so the pool is sized correctly regardless of which task runs.
     */
    public static int redisPoolSize(PropertiesProvider propertiesProvider) {
        int parallelism = propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(DEFAULT_PARALLELISM);
        int floor = parallelism + REDIS_POOL_SIZE_OVERHEAD;
        return propertiesProvider.get(REDIS_POOL_SIZE_OPT).map(Integer::parseInt).map(configured -> Math.max(configured, floor)).orElse(floor);
    }

    @Provides @Singleton
    org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor provideAmqpInterlocutor() {
        AmqpInterlocutor amqp = null;
        try {
            amqp = new AmqpInterlocutor(propertiesProvider);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
        addCloseable(amqp);
        return amqp;
    }

    @Provides @Singleton
    TemporalInterlocutor provideTemporal(final PropertiesProvider propertiesProvider) throws InterruptedException {
        return new TemporalInterlocutor(propertiesProvider);
    }

    @Provides @Singleton
    TaskManagerMemory provideTaskManagerMemory(
            DatashareTaskFactory taskFactory, TaskRepository taskRepository, PropertiesProvider propertiesProvider) {
        return new TaskManagerMemory(
                taskFactory, taskRepository, propertiesProvider, new CountDownLatch(1));
    }

    @Provides @Singleton
    TaskManagerRedis provideTaskManagerRedis(
            RedissonClient redissonClient, PropertiesProvider propertiesProvider, TaskRepository taskRepository) {
        return new TaskManagerRedis(
                redissonClient, taskRepository, Utils.getRoutingStrategy(propertiesProvider), null,
                Integer.parseInt(propertiesProvider.get(TASK_MANAGER_POLLING_INTERVAL_OPT).orElse("5000")));
    }

    @Provides @Singleton
    TaskManagerAmqp provideTaskManagerAmqp(
            AmqpInterlocutor amqp,
            TaskRepository taskRepository, PropertiesProvider propertiesProvider) throws IOException {
        return new TaskManagerAmqp(
                amqp, taskRepository, Utils.getRoutingStrategy(propertiesProvider), null,
                Integer.parseInt(propertiesProvider.get(TASK_MANAGER_POLLING_INTERVAL_OPT).orElse("5000")));
    }

    @Provides @Singleton
    TaskManagerTemporal provideTaskManagerTemporal(
            TemporalInterlocutor temporal, TaskRepository taskRepository, PropertiesProvider propertiesProvider) {
        return new TaskManagerTemporal(
                temporal, taskRepository, Utils.getRoutingStrategy(propertiesProvider));
    }

    @Provides @Singleton
    TaskRepositoryMemory provideTaskRepositoryMemory() {
        return new TaskRepositoryMemory();
    }

    @Provides @Singleton
    TaskRepositoryRedis provideTaskRepositoryRedis(RedissonClient redissonClient) {
        return new TaskRepositoryRedis(redissonClient);
    }

    @Provides @Singleton
    TaskSupplierRedis provideTaskSupplierRedis(RedissonClient redissonClient, PropertiesProvider propertiesProvider) {
        return new TaskSupplierRedis(redissonClient, Utils.getRoutingKey(propertiesProvider));
    }

    @Provides @Singleton
    TaskSupplierAmqp provideTaskSupplierAmqp(
            org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor amqp, PropertiesProvider propertiesProvider) throws IOException {
        return new TaskSupplierAmqp(amqp, Utils.getRoutingKey(propertiesProvider));
    }

    @Provides @Singleton
    DocumentCollectionFactory<Path> provideScanQueue(final PropertiesProvider propertiesProvider) {
        return switch (getQueueType(propertiesProvider, QUEUE_TYPE_OPT, DEFAULT_QUEUE_TYPE)) {
            case MEMORY -> new MemoryDocumentCollectionFactory<>(propertiesProvider);
            case REDIS, AMQP, TEMPORAL -> new RedisDocumentCollectionFactory<>(propertiesProvider, get(RedissonClient.class));
        };
    }

    @Provides @Singleton
    DocumentCollectionFactory<String> provideIndexQueue(final PropertiesProvider propertiesProvider) {
        return switch (getQueueType(propertiesProvider, QUEUE_TYPE_OPT, DEFAULT_QUEUE_TYPE)) {
            case MEMORY -> new MemoryDocumentCollectionFactory<>(propertiesProvider);
            case REDIS, AMQP, TEMPORAL -> new RedisDocumentCollectionFactory<>(propertiesProvider, get(RedissonClient.class));
        };
    }

    @Provides @Singleton
    AsyncSearchStore provideAsyncSearchStore(final PropertiesProvider propertiesProvider) {
        return switch (getQueueType(propertiesProvider, QUEUE_TYPE_OPT, DEFAULT_QUEUE_TYPE)) {
            case MEMORY -> new MemoryAsyncSearchStore();
            case REDIS, AMQP, TEMPORAL -> new RedisAsyncSearchStore(get(RedissonClient.class));
        };
    }

    @Provides @Singleton
    Authorizer provideAuthorizer(CasbinRuleAdapter adapter, Provider<RedissonClient> redissonProvider) throws IOException {
        if (QueueType.REDIS.name().equals(propertiesProvider.get(BUS_TYPE_OPT).orElse(null))) {
            // Event-driven: RTopic notifies all instances immediately on policy change.
            // startAutoLoadPolicy backstop defaults to 0 (disabled); set policyReloadInterval
            // explicitly to enable periodic reloads as a hedge against missed publications.
            long interval = parseInt(propertiesProvider.get(POLICY_RELOAD_INTERVAL_OPT).orElse("0"));
            PolicyWatcher watcher = new PolicyWatcher(redissonProvider.get());
            Authorizer authorizer = new Authorizer(adapter, watcher);
            authorizer.startAutoLoadPolicy(interval);
            addCloseable(authorizer);
            return authorizer;
        }
        // Fallback: periodic reload from DB when Redis is not available. Default to 30s.
        long interval = parseInt(propertiesProvider.get(POLICY_RELOAD_INTERVAL_OPT).orElse(String.valueOf(DEFAULT_POLICY_RELOAD_INTERVAL)));
        Authorizer authorizer = new Authorizer(adapter, interval);
        addCloseable(authorizer);
        return authorizer;
    }

    @Provides @Singleton
    Indexer provideIndexer() {
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(createESClient(propertiesProvider), propertiesProvider);
        addCloseable(indexer);
        return indexer;
    }

    @Provides @Singleton
    UserStore provideUserStore(final Injector injector) {
        Class<? extends UserStore> providerClass = resolveUserStoreClass();
        logger.info("setting auth users provider to {}", providerClass);
        UserStore userStore = injector.getInstance(providerClass);
        if (userStore instanceof Closeable closeable) {
            addCloseable(closeable);
        }
        return userStore;
    }

    @Provides @Singleton
    Users provideUsers(UserStore userStore, UsersIdProviderCache usersIdProviderCache) {
        boolean authModeRequiringCache = isAuthModeRequiringCache();


        if (authModeRequiringCache) {
            return new Users() {
                @Override
                public net.codestory.http.security.User find(String login) {
                    net.codestory.http.security.User user = usersIdProviderCache.find(login);
                    return user != null ? user : userStore.find(login);
                }

                @Override
                public net.codestory.http.security.User find(String login, String password) {
                    net.codestory.http.security.User user = usersIdProviderCache.find(login, password);
                    return user != null ? user : userStore.find(login, password);
                }
            };
        }

        return userStore;
    }

    static Class<? extends UserStore> classFor(AuthUsersProvider provider) {
        return switch (provider) {
            case DATABASE -> UsersInDb.class;
            case REDIS -> UsersInRedis.class;
        };
    }

    @SuppressWarnings("unchecked")
    Class<? extends UserStore> resolveUserStoreClass() {
        String raw = propertiesProvider.get(AUTH_USERS_PROVIDER_OPT).orElse(AuthUsersProvider.DATABASE.cliName);
        Optional<AuthUsersProvider> provider = AuthUsersProvider.tryFromString(raw);
        if (provider.isPresent()) {
            return classFor(provider.get());
        }
        try {
            Class<?> cls = Class.forName(raw, true, ClassLoader.getSystemClassLoader());
            if (!UserStore.class.isAssignableFrom(cls)) {
                logger.warn("\"{}\" does not implement UserStore. Setting provider to UsersInDb", raw);
                return UsersInDb.class;
            }
            return (Class<? extends UserStore>) cls;
        } catch (ClassNotFoundException | ClassCastException e) {
            logger.warn("\"{}\" auth users provider class not found or invalid. Setting provider to UsersInDb", raw);
            return UsersInDb.class;
        }
    }

    @Provides @Singleton
    UsersIdProviderCache provideUsersIdProviderCache(final Injector injector) {
        boolean requiresCache = isAuthModeRequiringCache();
        if (requiresCache) {
            UsersIdProviderRedisCache cache = injector.getInstance(UsersIdProviderRedisCache.class);
            addCloseable(cache);
            return cache;
        }
        return UsersIdProviderCache.NO_CACHE;

    }

    @NotNull
    private Boolean isAuthModeRequiringCache() {
        return propertiesProvider.get(AUTH_MODE_OPT)
                .filter(m -> !m.isEmpty())
                .map(m -> {
                    try {
                        // only OAuth and YesCookie require redis cache for sessions
                        return AuthMode.fromString(m) == AuthMode.OAUTH || AuthMode.fromString(m) == AuthMode.YES_COOKIE;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    @Provides @Singleton
    LanguageGuesser provideLanguageGuesser() throws IOException {
        return new OptimaizeLanguageGuesser();
    }

    @Provides @Singleton
    PipelineRegistry providePipelineRegistry(final PropertiesProvider propertiesProvider) {
        PipelineRegistry pipelineRegistry = new PipelineRegistry(propertiesProvider);
        pipelineRegistry.register(EmailPipeline.class);
        pipelineRegistry.register(Pipeline.Type.CORENLP);
        return pipelineRegistry;
    }

    public Properties properties() {
        return propertiesProvider.getProperties();
    }

    public Configuration createWebConfiguration() {
        return routes -> addModeConfiguration(
                defaultRoutes(
                            addCorsFilter(routes,
                                    propertiesProvider
                            )
                )
        );
    }

    public Routes addCorsFilter(Routes routes, PropertiesProvider provider) {
        String cors = provider.get("cors").orElse("no-cors");
        if (!cors.equals("no-cors")) {
            routes.filter(new CorsFilter(cors));
        }
        return routes;
    }

    public Routes addPluginsConfiguration(Routes routes) {
        String pluginsDir = getPluginsDir();
        if (pluginsDir == null) {
            return routes;
        }
        if (!new File(pluginsDir).isDirectory()) {
            logger.warn("Plugins directory not found: " + pluginsDir);
            return routes;
        }
        return routes.bind(PLUGINS_BASE_URL, Paths.get(pluginsDir).toFile());
    }

     public Routes addExtensionsConfiguration(Routes routes) {
         ExtensionLoader extensionLoader = new ExtensionLoader(Paths.get(ofNullable(getExtensionsDir()).orElse("./extensions")));
         if (extensionLoader.extensionsDir != null) {
            try {
                extensionLoader.load(routes::add, this::isEligibleForLoading);
                extensionLoader.load(cls -> JsonObjectMapper.registerSubtypes(new NamedType(cls)), this::isJsonType);
                get(PipelineRegistry.class).load(extensionLoader);
            } catch (FileNotFoundException e) {
                logger.warn("Extensions directory not found: {}", extensionLoader.extensionsDir);
            }
        }
        return routes;
    }

    protected abstract Routes addModeConfiguration(final Routes routes);

    void configurePersistence() {
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        Repository repository = repositoryFactory.createRepository();
        // TODO: remove this in few stable releases (cf git commit comment)
        repository.temporaryFixLiquibaseIds();
        bind(Repository.class).toInstance(repository);
        bind(ApiKeyRepository.class).toInstance(repositoryFactory.createApiKeyRepository());
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        bind(CasbinRuleAdapter.class).toInstance(repositoryFactory.createCasbinRuleRepository());
        bind(UserAdminService.class).to(UserAdminServiceImpl.class).in(Singleton.class);
        bind(ProjectAdminService.class).to(ProjectAdminServiceImpl.class).in(Singleton.class);

        TaskRepositoryType taskRepositoryType = TaskRepositoryType.valueOf(propertiesProvider.get(TASK_REPOSITORY_OPT).orElse("DATABASE"));
        switch ( taskRepositoryType ) {
            case MEMORY -> bind(TaskRepository.class).to(TaskRepositoryMemory.class);
            case REDIS -> bind(TaskRepository.class).to(TaskRepositoryRedis.class);
            case DATABASE -> {
                JooqTaskRepository jooqTaskRepository = new JooqTaskRepository(repositoryFactory.getDataSource(), repositoryFactory.guessSqlDialect());
                bind(TaskRepository.class).toInstance(jooqTaskRepository);
            }
        }
        repositoryFactory.initDatabase();
    }

    private ExecutorService runTaskWorkerLoop() {
        if (getTaskWorkersNb() > 0) {
            List<TaskWorkerLoop> workers = IntStream.range(0, getTaskWorkersNb())
                .mapToObj(i -> new TaskWorkerLoop(get(DatashareTaskFactory.class), get(TaskSupplier.class),
                    getProgressMinIntervalS())).toList();
            workers.forEach(this::addCloseable);
            workers.forEach(executorService::submit);
        }
        return executorService;
    }

    private ExecutorService runTemporalWorkers() {
        if (getTaskWorkersNb() > 0) {
            TemporalInterlocutor temporal = get(TemporalInterlocutor.class);
            executorService.submit(() -> {
                closeables.add(temporal.discoverWorkflows(getTaskWorkersNb(), get(DatashareTaskFactory.class),
                                get(TaskRepository.class),
                                Utils.getRoutingStrategy(propertiesProvider),
                                new Group(TaskGroupType.Java)));
            });
        }
        return executorService;
    }

    private Routes defaultRoutes(final Routes routes) {
        routes.setIocAdapter(new GuiceAdapter(injector))
                .add(RootResource.class)
                .add(SettingsResource.class)
                .add(OpenApiResource.class)
                .add(StatusResource.class)
                .setExtensions(new Extensions() {
                    @Override
                    public ObjectMapper configureOrReplaceObjectMapper(ObjectMapper defaultObjectMapper, Env env) {
                        ObjectMapper mapper = JsonObjectMapper.getMapper();
                        mapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                        return mapper;
                    }
                });
        addExtensionsConfiguration(routes);
        routes.filter(StatusCidrFilter.class);
        addModeConfiguration(routes);
        addPluginsConfiguration(routes);
        return routes;
    }

    private String getExtensionsDir() {
        return propertiesProvider.getProperties().getProperty(PropertiesProvider.EXTENSIONS_DIR_OPT);
    }

    private String getPluginsDir() {
        return propertiesProvider.getProperties().getProperty(PropertiesProvider.PLUGINS_DIR_OPT);
    }

    private boolean isEligibleForLoading(Class<?> c) {
        return c.isAnnotationPresent(Prefix.class) || c.isAnnotationPresent(Get.class);
    }

    private boolean isJsonType(Class<?> c) {
        return (c.isAnnotationPresent(JsonTypeName.class) || c.isAnnotationPresent(JsonTypeInfo.class)) && Serializable.class.isAssignableFrom(c);
    }

    @NotNull
    public static Mode getMode(Properties properties) {
        return Mode.valueOf(ofNullable(properties).orElse(new Properties()).getProperty(MODE_OPT));
    }

    public static QueueType getQueueType(PropertiesProvider properties, String propertyName, QueueType defaultQueueType){
        return QueueType.valueOf(
            ofNullable(properties).orElse(new PropertiesProvider()).
                get(propertyName).orElse(defaultQueueType.name()).toUpperCase());
    }

    public QueueType getCurrentBatchQueueType() {
        return getQueueType(propertiesProvider, BATCH_QUEUE_TYPE_OPT, DEFAULT_BATCH_QUEUE_TYPE);
    }

    public void addCloseable(Closeable client) {
        closeables.add(client);
    }

    public void close() throws IOException {
        closeables.descendingIterator().forEachRemaining(closeable -> {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.error("error closing {}", closeable, e);
            }
        });
    }

    public Thread closeThread() {
        return new Thread(() -> {
            try {
                close();
            } catch (IOException e) {
                logger.error("Error closing app", e);
            }
        });
    }
}
