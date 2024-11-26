package org.icij.datashare.mode;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
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
import org.icij.datashare.TesseractOCRParserWrapper;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskModifier;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.queue.MemoryBlockingQueue;
import org.icij.datashare.com.queue.RedisBlockingQueue;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.extension.ExtensionLoader;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.*;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.nlp.OptimaizeLanguageGuesser;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.icij.datashare.tasks.TaskManagerAmqp;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TaskManagerRedis;
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
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.PluginService.PLUGINS_BASE_URL;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public abstract class CommonMode extends AbstractModule {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    public static final String DS_TASKS_QUEUE_NAME = "ds:task:manager:queue";
    public static final String DS_TASK_MANAGER_MAP_NAME = "ds:task:manager:tasks";
    public static final String DS_TASK_MANAGER_QUEUE_NAME = "ds:task:manager";

    protected final PropertiesProvider propertiesProvider;
    protected final Mode mode;
    private final Injector injector;
    private PipelineRegistry pipelineRegistry;

    protected CommonMode(Properties properties) {
        propertiesProvider = properties == null ? new PropertiesProvider() :
                new PropertiesProvider(properties.getProperty(PropertiesProvider.SETTINGS_FILE_PARAMETER_KEY)).overrideWith(properties);
        this.mode = getMode(properties);
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
        switch (getMode(properties)) {
            case NER:
                return new NerMode(properties);
            case LOCAL:
                return new LocalMode(properties);
            case EMBEDDED:
                return new EmbeddedMode(properties);
            case SERVER:
                return new ServerMode(properties);
            case TASK_WORKER:
            case CLI:
                return new CliMode(properties);
            default:
                throw new IllegalStateException("unknown mode : " + properties.getProperty(MODE_OPT));
        }
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

        RedissonClient redissonClient = null;
        if ( hasProperty(QueueType.REDIS) ) {
            redissonClient = new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create();
            bind(RedissonClient.class).toInstance(redissonClient);
        }
        if ( hasProperty(QueueType.AMQP) ) {
            try {
                AmqpInterlocutor amqp = new AmqpInterlocutor(propertiesProvider);
                amqp.createAllPublishChannels();
                bind(AmqpInterlocutor.class).toInstance(amqp);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        QueueType batchQueueType = getQueueType(propertiesProvider, BATCH_QUEUE_TYPE_OPT, QueueType.MEMORY);
        switch ( batchQueueType ) {
            case REDIS:
                configureBatchQueuesRedis(redissonClient);
                bind(DatashareTaskManager.class).to(TaskManagerRedis.class);
                bind(TaskModifier.class).to(TaskSupplierRedis.class);
                bind(TaskSupplier.class).to(TaskSupplierRedis.class);
                break;
            case AMQP:
                configureBatchQueuesRedis(redissonClient);
                bind(DatashareTaskManager.class).to(TaskManagerAmqp.class);
                bind(TaskSupplier.class).to(TaskSupplierAmqp.class);
                bind(TaskModifier.class).to(TaskSupplierAmqp.class);
                break;
            default:
                configureBatchQueuesMemory();
                bind(DatashareTaskManager.class).to(TaskManagerMemory.class);
                bind(TaskModifier.class).to(TaskManagerMemory.class);
                bind(TaskSupplier.class).to(TaskManagerMemory.class);
        }

        ElasticsearchClient esClient = createESClient(propertiesProvider);
        bind(ElasticsearchClient.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();

        bind(TesseractOCRParserWrapper.class).toInstance(new TesseractOCRParserWrapper());

        configureIndexingQueues(propertiesProvider);
        pipelineRegistry = bindPipelineRegistry(propertiesProvider);
    }

    private void configureIndexingQueues(final PropertiesProvider propertiesProvider) {
        QueueType queueType = getQueueType(propertiesProvider, QUEUE_TYPE_OPT, QueueType.MEMORY);
        if ( queueType == QueueType.MEMORY ) {
            bind(new TypeLiteral<DocumentCollectionFactory<String>>(){}).toInstance(new MemoryDocumentCollectionFactory<>());
            bind(new TypeLiteral<DocumentCollectionFactory<Path>>() {}).toInstance(new MemoryDocumentCollectionFactory<>());
        } else {
            bind(new TypeLiteral<DocumentCollectionFactory<String>>(){}).to(new TypeLiteral<RedisDocumentCollectionFactory<String>>(){});
            bind(new TypeLiteral<DocumentCollectionFactory<Path>>(){}).to(new TypeLiteral<RedisDocumentCollectionFactory<Path>>(){});
        }
    }

    private void configureBatchQueuesMemory() {
        bind(new TypeLiteral<BlockingQueue<Task<?>>>(){}).toInstance(new MemoryBlockingQueue<>(DS_TASKS_QUEUE_NAME));
    }

    private void configureBatchQueuesRedis(RedissonClient redissonClient) {
        bind(new TypeLiteral<BlockingQueue<Task<?>>>(){}).toInstance(new RedisBlockingQueue<>(redissonClient, DS_TASKS_QUEUE_NAME, new org.icij.datashare.asynctasks.TaskManagerRedis.RedisCodec<>(Task.class)));
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
                pipelineRegistry.load(extensionLoader);
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
        repositoryFactory.initDatabase();
    }

    protected boolean hasProperty(QueueType queueType) {
        return propertiesProvider.getProperties().contains(queueType.name());
    }

    protected PipelineRegistry bindPipelineRegistry(final PropertiesProvider propertiesProvider) {
        PipelineRegistry pipelineRegistry = new PipelineRegistry(propertiesProvider);
        pipelineRegistry.register(EmailPipeline.class);
        pipelineRegistry.register(Pipeline.Type.CORENLP);
        bind(PipelineRegistry.class).toInstance(pipelineRegistry);
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
        return pipelineRegistry;
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
        addModeConfiguration(routes);
        addExtensionsConfiguration(routes);
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
}
