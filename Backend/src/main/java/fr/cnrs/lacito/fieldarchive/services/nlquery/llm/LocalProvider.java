package fr.cnrs.lacito.fieldarchive.services.nlquery.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import fr.cnrs.lacito.fieldarchive.dtos.ProviderStatusDto;
import fr.cnrs.lacito.fieldarchive.services.nlquery.ApiKeyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A model running on the user's computer through Ollama's OpenAI-compatible API. Free, no key,
 * and no project data leaves the machine; slower and less reliable than the paid agents.
 */
@Component
public class LocalProvider extends OpenAiProvider {

    private final String baseUrl;
    private final String localModel;
    private final int localTimeoutSeconds;
    private final int localTotalTimeoutSeconds;
    private OpenAIClient localClient;

    public LocalProvider(ApiKeyStore keys,
                         @Value("${fieldarchive.nlquery.local.base-url:http://localhost:11434/v1}") String baseUrl,
                         @Value("${fieldarchive.nlquery.local.model:gpt-oss:20b}") String localModel,
                         @Value("${fieldarchive.nlquery.max-output-tokens:16000}") long maxOutputTokens,
                         @Value("${fieldarchive.nlquery.local.timeout-seconds:300}") int localTimeoutSeconds,
                         @Value("${fieldarchive.nlquery.local.total-timeout-seconds:900}") int localTotalTimeoutSeconds) {
        super(keys, localModel, maxOutputTokens, localTotalTimeoutSeconds);
        this.baseUrl = baseUrl;
        this.localModel = localModel;
        this.localTimeoutSeconds = localTimeoutSeconds;
        this.localTotalTimeoutSeconds = localTotalTimeoutSeconds;
    }

    @Override public String id() { return "local"; }
    @Override public String label() { return "Local model (Ollama)"; }
    @Override public String model() { return localModel; }
    @Override public ContextBudget contextBudget() { return ContextBudget.COMPACT; }
    @Override public int totalTimeoutSeconds() { return localTotalTimeoutSeconds; }
    @Override protected boolean local() { return true; }
    @Override protected String agentName() { return "Local model"; }

    @Override
    protected synchronized OpenAIClient client() {
        if (localClient == null) {
            localClient = OpenAIOkHttpClient.builder()
                    .baseUrl(baseUrl)
                    .apiKey("ollama") // required by the client, ignored by Ollama
                    .timeout(Duration.ofSeconds(localTimeoutSeconds))
                    .maxRetries(0)
                    .build();
        }
        return localClient;
    }

    @Override
    public void validateKey(String apiKey) {
        throw new UnsupportedOperationException("The local model does not use an API key");
    }

    private String ollamaRoot() {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return root.endsWith("/v1") ? root.substring(0, root.length() - 3) : root;
    }

    @Override
    public ProviderStatusDto status() {
        ProviderStatusDto s = new ProviderStatusDto();
        s.id = id();
        s.label = label();
        s.model = localModel;
        s.free = true;
        s.keySource = null;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(ollamaRoot() + "/api/tags"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode tags = JSON.readTree(r.body());
            boolean installed = false;
            for (JsonNode m : tags.path("models")) {
                String name = m.path("name").asText("");
                if (name.equals(localModel) || name.equals(localModel + ":latest")) installed = true;
            }
            s.available = installed;
            s.reason = installed ? null
                    : "Model " + localModel + " is not installed: run `ollama pull " + localModel + "`";
        } catch (Exception e) {
            s.available = false;
            s.reason = "Ollama is not running (install it from ollama.com)";
        }
        return s;
    }
}
