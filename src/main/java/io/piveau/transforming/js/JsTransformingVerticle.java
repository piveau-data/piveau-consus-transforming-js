package io.piveau.transforming.js;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.piveau.pipe.connector.PipeContext;
import io.piveau.transforming.repositories.GitRepository;
import io.piveau.utils.JenaUtils;
import io.piveau.vocabularies.Prefixes;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public class JsTransformingVerticle extends AbstractVerticle {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String ADDRESS = "io.piveau.pipe.transformation.js.queue";

    private static final String ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH = "PIVEAU_REPOSITORY_DEFAULT_BRANCH";

    private Cache<String, ScriptEngine> cache;

    private WebClient client;

    private String defaultBranch;

    @Override
    public void start(Promise<Void> startPromise) {
        vertx.eventBus().consumer(ADDRESS, this::handlePipe);
        client = WebClient.create(vertx);

        CacheManager cacheManager = CacheManagerBuilder.newCacheManagerBuilder()
                .withCache("transformer", CacheConfigurationBuilder.newCacheConfigurationBuilder(String.class, ScriptEngine.class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder().heap(50, EntryUnit.ENTRIES))
                .withExpiry(ExpiryPolicyBuilder.timeToIdleExpiration(Duration.ofHours(12))))
                .build(true);

        cache = cacheManager.getCache("transformer", String.class, ScriptEngine.class);

        ConfigStoreOptions envStoreOptions = new ConfigStoreOptions()
                .setType("env")
                .setConfig(new JsonObject().put("keys", new JsonArray().add(ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH)));
        ConfigRetriever retriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(envStoreOptions));
        retriever.getConfig(ar -> {
            if (ar.succeeded()) {
                defaultBranch = ar.result().getString(ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH, "master");
                startPromise.complete();
            } else {
                startPromise.fail(ar.cause());
            }
        });
        retriever.listen(change -> defaultBranch = change.getNewConfiguration().getString(ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH, "master"));
    }

    private void handlePipe(Message<PipeContext> message) {
        PipeContext pipeContext = message.body();
        pipeContext.log().trace("Incoming pipe");
        ObjectNode config = (ObjectNode)pipeContext.getConfig();

        ObjectNode dataInfo = pipeContext.getDataInfo();
        if (dataInfo.hasNonNull("content") && dataInfo.get("content").asText().equals("identifierList")) {
            pipeContext.log().trace("Passing pipe");
            pipeContext.pass(client);
            return;
        }

        String runId = pipeContext.getPipe().getHeader().getRunId();
        ScriptEngine engine = cache.get(runId);
        if (engine == null) {
            String script;
            if ("repository".equalsIgnoreCase(config.path("scriptType").textValue())) {
                ObjectNode repository = (ObjectNode)config.path("repository");
                String uri = repository.path("uri").textValue();
                String branch = repository.path("branch").asText(defaultBranch);
                String username = repository.path("username").textValue();
                String token = repository.path("token").textValue();
                GitRepository gitRepo = GitRepository.open(uri, username, token, branch);
                Path file = gitRepo.resolve(repository.path("script").textValue());
                script = vertx.fileSystem().readFileBlocking(file.toString()).toString();
            } else {
                script = config.path("script").textValue();
            }

            try {
                engine = new ScriptEngineManager().getEngineByName("JavaScript");
                ScriptContext context = engine.getContext();
                context.setAttribute("name", "JavaScript", ScriptContext.ENGINE_SCOPE);

                engine.eval(script);
                engine.eval("function executeTransformation(obj) { return JSON.stringify(transforming(JSON.parse(obj))) }");

                JsonNode params = config.path("params");
                if (!params.isMissingNode()) {
                    engine.eval("var params = " + config.path("params").toString() + ";");
                }

                if (config.path("single").asBoolean()) {
                    cache.put(runId, engine);
                }
            } catch (ScriptException e) {
                log.error("initializing script template", e);
                pipeContext.log().error("Initialize script", e);
            }
        }

        ObjectNode info = pipeContext.getDataInfo();

        Invocable jsInvoke = (Invocable) engine;
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
            mapper.configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);
            mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

            JsonNode input = mapper.readTree(pipeContext.getStringData());
            Object output = jsInvoke.invokeFunction("executeTransformation", input.toString());

            String out = output.toString();

            InputStream stream = new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8));

            Dataset dataset = DatasetFactory.create();
            try {
                RDFDataMgr.read(dataset, stream, Lang.JSONLD);
                Model model = dataset.getDefaultModel();
                model.setNsPrefixes(Prefixes.DCATAP_PREFIXES);
                String outputFormat = config.path("outputFormat").asText("application/n-triples");
                String result = JenaUtils.write(model, outputFormat);
                pipeContext.setResult(result, outputFormat, info).forward(client);
            } catch (Exception e) {
                log.error("normalizing json-ld", e);
                pipeContext.log().error(info.toString(), e);
            }

            pipeContext.log().info("Data transformed: {}", info);

        } catch (IOException | NoSuchMethodException | ScriptException e) {
            log.error("transforming data", e);
            pipeContext.log().error(info.toString(), e);
        }

    }

}
