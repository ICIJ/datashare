package org.icij.datashare.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.codestory.http.Configuration;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.injection.GuiceAdapter;
import net.codestory.http.misc.Env;
import net.codestory.http.routes.Routes;
import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.com.MemoryDataBus;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.RedisDataBus;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.extension.ExtensionLoader;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.extract.RedisUserReportMap;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.nlp.OptimaizeLanguageGuesser;
import org.icij.datashare.tasks.DocumentCollectionFactory;
import org.icij.datashare.tasks.MemoryDocumentCollectionFactory;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.web.RootResource;
import org.icij.datashare.web.SettingsResource;
import org.icij.datashare.web.StatusResource;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.PluginService.PLUGINS_BASE_URL;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public class CommonMode extends AbstractModule {
    protected final PropertiesProvider propertiesProvider;

    protected CommonMode(Properties properties) {
        propertiesProvider = properties == null ? new PropertiesProvider() :
                new PropertiesProvider(properties.getProperty(PropertiesProvider.SETTINGS_FILE_PARAMETER_KEY)).mergeWith(properties);
    }

    CommonMode(final Map<String, String> map) {
        this(PropertiesProvider.fromMap(map));
    }

    public static CommonMode create(final Properties properties) {
        switch (Mode.valueOf(ofNullable(properties).orElse(new Properties()).getProperty("mode"))) {
            case NER:
                return new NerMode(properties);
            case LOCAL:
                return new LocalMode(properties);
            case EMBEDDED:
                return new EmbeddedMode(properties);
            case SERVER:
                return new ServerMode(properties);
            case CLI:
            case BATCH:
                return new CliMode(properties);
            default:
                throw new IllegalStateException("unknown mode : " + properties.getProperty("mode"));
        }
    }

    @Override
    protected void configure() {
        bind(PropertiesProvider.class).toInstance(propertiesProvider);
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);

        bind(BlockingQueue.class).to(getBlockingQueueClassInstance(
                propertiesProvider.get("batchQueueType").orElse("java.util.concurrent.LinkedBlockingQueue"))).asEagerSingleton();

        RestHighLevelClient esClient = createESClient(propertiesProvider);
        bind(RestHighLevelClient.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();
        bind(TaskManager.class).toInstance(new TaskManager(propertiesProvider));
        install(new FactoryModuleBuilder().build(TaskFactory.class));

        if ("memory".equals(propertiesProvider.getProperties().get("queueType"))) {
            bind(DocumentCollectionFactory.class).to(MemoryDocumentCollectionFactory.class).asEagerSingleton();
        } else {
            install(new FactoryModuleBuilder().
                implement(DocumentQueue.class, RedisUserDocumentQueue.class).
                implement(ReportMap.class, RedisUserReportMap.class).
                build(DocumentCollectionFactory.class));
        }
        DataBus dataBus;
        if ("memory".equals(propertiesProvider.getProperties().get("busType"))) {
            dataBus = new MemoryDataBus();
        } else {
            dataBus = new RedisDataBus(propertiesProvider);
        }
        bind(DataBus.class).toInstance(dataBus);
        bind(Publisher.class).toInstance(dataBus);

        PipelineRegistry pipelineRegistry = new PipelineRegistry(propertiesProvider);
        pipelineRegistry.register(EmailPipeline.class);
        pipelineRegistry.register(Pipeline.Type.CORENLP);
        try {
            pipelineRegistry.load();
        } catch (FileNotFoundException e) {
            LoggerFactory.getLogger(getClass()).info("plugin dir not found " + e.getMessage());
        }
        bind(PipelineRegistry.class).toInstance(pipelineRegistry);
        bind(StatusResource.class).asEagerSingleton();
    }

    public Properties properties() {
        return propertiesProvider.getProperties();
    }

    public Configuration createWebConfiguration() {
        return routes -> addModeConfiguration(defaultRoutes(routes, propertiesProvider));
    }

    protected Routes addModeConfiguration(final Routes routes) {return routes;}

    void configurePersistence() {
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(Repository.class).toInstance(repositoryFactory.createRepository());
        bind(ApiKeyRepository.class).toInstance(repositoryFactory.createApiKeyRepository());
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        repositoryFactory.initDatabase();
    }

    private Routes defaultRoutes(final Routes routes, PropertiesProvider provider) {
        routes.setIocAdapter(new GuiceAdapter(this))
                .add(RootResource.class)
                .add(SettingsResource.class)
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

        String cors = provider.get("cors").orElse("no-cors");
        if (!cors.equals("no-cors")) {
            routes.filter(new CorsFilter(cors));
        }
        return routes;
    }

    Routes addExtensionConfiguration(Routes routes) {
        String pluginsDir = propertiesProvider.getProperties().getProperty(PropertiesProvider.EXTENSIONS_DIR);
        if (pluginsDir != null) {
            try {
                new ExtensionLoader(Paths.get(pluginsDir)).load((Consumer<Class<?>>)routes::add,
                        c -> c.isAnnotationPresent(Prefix.class) || c.isAnnotationPresent(Get.class));
            } catch (FileNotFoundException e) {
                LoggerFactory.getLogger(getClass()).info("plugin dir not found", e);
            }
        }
        return routes;
    }

    private Class<? extends BlockingQueue<String>> getBlockingQueueClassInstance(String className) {
        try {
            return (Class<? extends BlockingQueue<String>>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
