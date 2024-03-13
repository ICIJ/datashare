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
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.extension.ExtensionLoader;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryBlockingQueue;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.extract.RedisBlockingQueue;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.extract.RedisUserReportMap;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.nlp.OptimaizeLanguageGuesser;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerAmqp;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TaskManagerRedis;
import org.icij.datashare.tasks.TaskModifier;
import org.icij.datashare.tasks.TaskSupplier;
import org.icij.datashare.tasks.TaskSupplierAmqp;
import org.icij.datashare.tasks.TaskView;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.web.OpenApiResource;
import org.icij.datashare.web.RootResource;
import org.icij.datashare.web.SettingsResource;
import org.icij.datashare.web.StatusResource;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.extract.report.ReportMap;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public abstract class CommonMode extends AbstractModule {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    public static final String DS_BATCHSEARCH_QUEUE_NAME = "ds:batchsearch:queue";
    public static final String DS_BATCHDOWNLOAD_QUEUE_NAME = "ds:batchdownload:queue";
    public static final String DS_TASK_MANAGER_QUEUE_NAME = "ds:task:manager";
    public static final String DS_TASK_MANAGER_MAP_NAME = "ds:task:manager:tasks";
    protected final PropertiesProvider propertiesProvider;
    protected final Mode mode;
    private final Injector injector;

    protected CommonMode(Properties properties) {
        propertiesProvider = properties == null ? new PropertiesProvider() :
                new PropertiesProvider(properties.getProperty(PropertiesProvider.SETTINGS_FILE_PARAMETER_KEY)).mergeWith(properties);
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
            case BATCH_SEARCH:
            case BATCH_DOWNLOAD:
            case CLI:
                return new CliMode(properties);
            default:
                throw new IllegalStateException("unknown mode : " + properties.getProperty("mode"));
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
        install(new FactoryModuleBuilder().build(TaskFactory.class));

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

        QueueType batchQueueType = QueueType.valueOf(propertiesProvider.get("batchQueueType").orElse(QueueType.MEMORY.name()));
        switch ( batchQueueType ) {
            case REDIS:
                configureBatchQueuesRedis(redissonClient);
                bind(TaskManager.class).to(TaskManagerRedis.class);
                bind(TaskModifier.class).to(TaskManagerRedis.class);
                bind(TaskSupplier.class).to(TaskManagerRedis.class);
                break;
            case AMQP:
                configureBatchQueuesRedis(redissonClient);
                bind(TaskManager.class).to(TaskManagerAmqp.class);
                bind(TaskSupplier.class).to(TaskSupplierAmqp.class);
                bind(TaskModifier.class).to(TaskSupplierAmqp.class);
                break;
            default:
                configureBatchQueuesMemory(propertiesProvider);
                bind(TaskManager.class).to(TaskManagerMemory.class);
                bind(TaskModifier.class).to(TaskManagerMemory.class);
                bind(TaskSupplier.class).to(TaskManagerMemory.class);
        }

        ElasticsearchClient esClient = createESClient(propertiesProvider);
        bind(ElasticsearchClient.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();

        bind(TesseractOCRParserWrapper.class).toInstance(new TesseractOCRParserWrapper());

        configureIndexingQueues(propertiesProvider);
        feedPipelineRegistry(propertiesProvider);
    }

    private void configureIndexingQueues(final PropertiesProvider propertiesProvider) {
        QueueType queueType = QueueType.valueOf(propertiesProvider.get("queueType").orElse(QueueType.MEMORY.name()));
        if ( queueType == QueueType.MEMORY ) {
            bind(new TypeLiteral<DocumentCollectionFactory<String>>(){}).toInstance(new MemoryDocumentCollectionFactory<>());
            bind(new TypeLiteral<DocumentCollectionFactory<Path>>() {}).toInstance(new MemoryDocumentCollectionFactory<>());
        } else {
            install(new FactoryModuleBuilder().
                    implement(new TypeLiteral<DocumentQueue<String>>(){}, new TypeLiteral<RedisUserDocumentQueue<String>>(){}).
                    implement(ReportMap.class, RedisUserReportMap.class).
                    build(new TypeLiteral<DocumentCollectionFactory<String>>(){}));
            install(new FactoryModuleBuilder().
                    implement(new TypeLiteral<DocumentQueue<Path>>(){}, new TypeLiteral<RedisUserDocumentQueue<Path>>(){}).
                    implement(ReportMap.class, RedisUserReportMap.class).
                    build(new TypeLiteral<DocumentCollectionFactory<Path>>(){}));
        }
    }

    private void configureBatchQueuesMemory(PropertiesProvider propertiesProvider) {
        bind(new TypeLiteral<BlockingQueue<String>>(){}).toInstance(new MemoryBlockingQueue<>(propertiesProvider, DS_BATCHSEARCH_QUEUE_NAME));
        bind(new TypeLiteral<BlockingQueue<TaskView<?>>>(){}).toInstance(new MemoryBlockingQueue<>(propertiesProvider, DS_BATCHDOWNLOAD_QUEUE_NAME));
    }

    private void configureBatchQueuesRedis(RedissonClient redissonClient) {
        bind(new TypeLiteral<BlockingQueue<String>>(){}).toInstance(new RedisBlockingQueue<>(redissonClient, DS_BATCHSEARCH_QUEUE_NAME));
        bind(new TypeLiteral<BlockingQueue<TaskView<?>>>(){}).toInstance(new RedisBlockingQueue<>(redissonClient, DS_BATCHDOWNLOAD_QUEUE_NAME));
    }

    void feedPipelineRegistry(final PropertiesProvider propertiesProvider) {
        PipelineRegistry pipelineRegistry = new PipelineRegistry(propertiesProvider);
        pipelineRegistry.register(EmailPipeline.class);
        pipelineRegistry.register(Pipeline.Type.CORENLP);
        try {
            pipelineRegistry.load();
        } catch (FileNotFoundException e) {
            logger.info("extensions dir not found " + e.getMessage());
        }
        bind(PipelineRegistry.class).toInstance(pipelineRegistry);
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
    }

    public Properties properties() {
        return propertiesProvider.getProperties();
    }

    public Configuration createWebConfiguration() {
        return routes -> addModeConfiguration(
                defaultRoutes(
                            addCorsFilter(routes,
                                    propertiesProvider
                            ),
                            propertiesProvider
                )
        );
    }

    protected abstract Routes addModeConfiguration(final Routes routes);

    void configurePersistence() {
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(Repository.class).toInstance(repositoryFactory.createRepository());
        bind(ApiKeyRepository.class).toInstance(repositoryFactory.createApiKeyRepository());
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        repositoryFactory.initDatabase();
    }

    private Routes defaultRoutes(final Routes routes, PropertiesProvider provider) {
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
        addExtensionConfiguration(routes);

        if (provider.get(PropertiesProvider.PLUGINS_DIR).orElse(null) != null) {
            routes.bind(PLUGINS_BASE_URL, Paths.get(provider.getProperties().getProperty(PropertiesProvider.PLUGINS_DIR)).toFile());
        }
        return routes;
    }

    Routes addExtensionConfiguration(Routes routes) {
        String extensionsDir = propertiesProvider.getProperties().getProperty(PropertiesProvider.EXTENSIONS_DIR);
        if (extensionsDir != null) {
            try {
                new ExtensionLoader(Paths.get(extensionsDir)).load((Consumer<Class<?>>)routes::add,
                        c -> c.isAnnotationPresent(Prefix.class) || c.isAnnotationPresent(Get.class));
            } catch (FileNotFoundException e) {
                logger.info("extensions dir not found", e);
            }
        }
        return routes;
    }

    protected boolean hasProperty(QueueType queueType) {
        return propertiesProvider.getProperties().contains(queueType.name());
    }

    private Routes addCorsFilter(Routes routes, PropertiesProvider provider) {
        String cors = provider.get("cors").orElse("no-cors");
        if (!cors.equals("no-cors")) {
            routes.filter(new CorsFilter(cors));
        }
        return routes;
    }

    @NotNull
    public static Mode getMode(Properties properties) {
        return Mode.valueOf(ofNullable(properties).orElse(new Properties()).getProperty("mode"));
    }
}
