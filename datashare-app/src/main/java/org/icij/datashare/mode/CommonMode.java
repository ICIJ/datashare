package org.icij.datashare.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
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
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskModifier;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskSupplier;
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
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.nlp.OptimaizeLanguageGuesser;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.TaskManagerAmqp;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TaskManagerRedis;
import org.icij.datashare.tasks.TaskRepositoryRedis;
import org.icij.datashare.tasks.TaskSupplierAmqp;
import org.icij.datashare.tasks.TaskSupplierRedis;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.PluginService.PLUGINS_BASE_URL;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_QUEUE_TYPE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.QUEUE_TYPE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_REPOSITORY_OPT;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public abstract class CommonMode extends AbstractModule implements Closeable {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    public static final String DS_TASK_MANAGER_MAP_NAME = "ds:task:manager:tasks";

    protected final PropertiesProvider propertiesProvider;
    protected final Mode mode;
    private final Injector injector;
    private final List<Closeable> closeables = new LinkedList<>();

    protected CommonMode(Properties properties) {
        propertiesProvider = properties == null ? new PropertiesProvider() :
                new PropertiesProvider(properties.getProperty(PropertiesProvider.SETTINGS_FILE_PARAMETER_KEY)).overrideWith(properties);
        this.mode = getMode(properties);
        
        // Eager load extension JARs before Guice injector creation
        String extensionsDir = getExtensionsDir();
        if (extensionsDir != null) {
            try {
                new ExtensionLoader(Paths.get(extensionsDir)).eagerLoadJars();
            } catch (FileNotFoundException e) {
                logger.warn("Failed to eager load extensions: {}", e.getMessage());
            }
        }
        
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

    @Override
    protected void configure() {
        bind(PropertiesProvider.class).toInstance(propertiesProvider);
        install(new FactoryModuleBuilder().build(DatashareTaskFactory.class));

        QueueType batchQueueType = getQueueType(propertiesProvider, BATCH_QUEUE_TYPE_OPT, QueueType.MEMORY);
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
            default:
                bind(TaskManager.class).to(TaskManagerMemory.class);
                bind(TaskModifier.class).to(TaskManagerMemory.class);
                bind(TaskSupplier.class).to(TaskManagerMemory.class);
        }
    }

    @Provides @Singleton
    RedissonClient provideRedissonClient() {
        CloseableRedissonClient redissonClient = new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).createCloseable();
        addCloseable(redissonClient);
        return redissonClient;
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
    DocumentCollectionFactory<Path> provideScanQueue(final PropertiesProvider propertiesProvider) {
        return switch (getQueueType(propertiesProvider, QUEUE_TYPE_OPT, QueueType.MEMORY)) {
            case MEMORY -> new MemoryDocumentCollectionFactory<>();
            case REDIS, AMQP -> new RedisDocumentCollectionFactory<>(propertiesProvider, get(RedissonClient.class));
        };
    }

    @Provides @Singleton
    DocumentCollectionFactory<String> provideIndexQueue(final PropertiesProvider propertiesProvider) {
        return switch (getQueueType(propertiesProvider, QUEUE_TYPE_OPT, QueueType.MEMORY)) {
            case MEMORY -> new MemoryDocumentCollectionFactory<>();
            case REDIS, AMQP -> new RedisDocumentCollectionFactory<>(propertiesProvider, get(RedissonClient.class));
        };
    }

    @Provides @Singleton
    Indexer provideIndexer() {
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(createESClient(propertiesProvider), propertiesProvider);
        addCloseable(indexer);
        return indexer;
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

    @Provides @Singleton
    ObjectMapper provideObjectMapper() {
        return MAPPER;
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
                extensionLoader.load((Consumer<Class<?>>) routes::add, this::isEligibleForLoading);
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
        bind(Repository.class).toInstance(repositoryFactory.createRepository());
        bind(ApiKeyRepository.class).toInstance(repositoryFactory.createApiKeyRepository());
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());

        TaskRepositoryType taskRepositoryType = TaskRepositoryType.valueOf(propertiesProvider.get(TASK_REPOSITORY_OPT).orElse("DATABASE"));
        switch ( taskRepositoryType ) {
            case MEMORY -> {
                bind(TaskRepository.class).to(TaskRepositoryMemory.class);
            }
            case REDIS -> {
                bind(TaskRepository.class).to(TaskRepositoryRedis.class);
            }
            case DATABASE -> {
                bind(TaskRepository.class).toInstance(new JooqTaskRepository(repositoryFactory.getDataSource(), repositoryFactory.guessSqlDialect()));
            }
        }
        repositoryFactory.initDatabase();
    }

    protected boolean hasProperty(QueueType queueType) {
        return propertiesProvider.getProperties().contains(queueType.name());
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
                        defaultObjectMapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                        return defaultObjectMapper;
                    }
                });
        addExtensionsConfiguration(routes);
        addModeConfiguration(routes);
        addPluginsConfiguration(routes);
        return routes;
    }

    private String getExtensionsDir() {
        return propertiesProvider.getProperties().getProperty(PropertiesProvider.EXTENSIONS_DIR);
    }

    private String getPluginsDir() {
        return propertiesProvider.getProperties().getProperty(PropertiesProvider.PLUGINS_DIR);
    }

    private boolean isEligibleForLoading(Class<?> c) {
        return c.isAnnotationPresent(Prefix.class) || c.isAnnotationPresent(Get.class);
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

    protected void addCloseable(Closeable client) {
        closeables.add(client);
    }

    public void close() throws IOException {
        closeables.forEach(rethrowConsumer(Closeable::close));
    }
}
