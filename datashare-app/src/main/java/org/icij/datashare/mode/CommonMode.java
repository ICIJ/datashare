package org.icij.datashare.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.codestory.http.Configuration;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.filters.Filter;
import net.codestory.http.injection.GuiceAdapter;
import net.codestory.http.misc.Env;
import net.codestory.http.routes.Routes;
import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.Mode;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.datashare.web.ConfigResource;
import org.icij.datashare.web.RootResource;

import java.util.Map;
import java.util.Properties;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public class CommonMode extends AbstractModule {
    protected final PropertiesProvider propertiesProvider;

    protected CommonMode(Properties properties) {
        propertiesProvider = properties == null ? new PropertiesProvider() :
                new PropertiesProvider(properties.getProperty(PropertiesProvider.CONFIG_FILE_PARAMETER_KEY)).mergeWith(properties);
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
            case SERVER:
                return new ServerMode(properties);
            case CLI:
            case BATCH:
                return new CliMode(properties);
            default:
                throw new IllegalStateException("unknown mode : " + properties.getProperty("mode"));
        }
    }

    public static boolean isBatch(Properties properties) {
        return Mode.valueOf(ofNullable(properties).orElse(new Properties()).getProperty("mode")) == Mode.BATCH;
    }

    @Override
    protected void configure() {
        bind(PropertiesProvider.class).toInstance(propertiesProvider);
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);

        RestHighLevelClient esClient = createESClient(propertiesProvider);
        bind(RestHighLevelClient.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();
        bind(TaskManager.class).toInstance(new TaskManager(propertiesProvider));
        install(new FactoryModuleBuilder().build(TaskFactory.class));
        bind(Publisher.class).to(RedisPublisher.class);
    }

    public Configuration createWebConfiguration() {
        return routes -> addModeConfiguration(defaultRoutes(routes, propertiesProvider));
    }

    protected Routes addModeConfiguration(final Routes routes) {return routes;}

    void configurePersistence() {
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(Repository.class).toInstance(repositoryFactory.createRepository());
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        repositoryFactory.initDatabase();
    }

    private Routes defaultRoutes(final Routes routes, PropertiesProvider provider) {
        routes.setIocAdapter(new GuiceAdapter(this))
                .add(RootResource.class)
                .add(ConfigResource.class)
                .setExtensions(new Extensions() {
                    @Override
                    public ObjectMapper configureOrReplaceObjectMapper(ObjectMapper defaultObjectMapper, Env env) {
                        defaultObjectMapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                        return defaultObjectMapper;
                    }
                })
                .filter(Filter.class);

        addModeConfiguration(routes);

        String cors = provider.get("cors").orElse("no-cors");
        if (!cors.equals("no-cors")) {
            routes.filter(new CorsFilter(cors));
        }
        return routes;
    }
}
