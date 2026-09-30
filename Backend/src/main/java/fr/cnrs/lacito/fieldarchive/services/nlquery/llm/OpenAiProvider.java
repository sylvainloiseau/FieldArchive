package fr.cnrs.lacito.fieldarchive.services.nlquery.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.*;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import fr.cnrs.lacito.fieldarchive.dtos.ProviderStatusDto;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.nlquery.ApiKeyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * ChatGPT, through the OpenAI Responses API. Stateless: {@code store: false}, and the encrypted
 * reasoning items are sent back with the rest of the transcript on every turn.
 * {@link LocalProvider} reuses this class against Ollama's OpenAI-compatible endpoint.
 */
@Component
@Primary
public class OpenAiProvider implements LlmProvider {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final String SUBMIT_ANSWER = "submit_answer";

    protected final ApiKeyStore keys;
    protected final String model;
    protected final long maxOutputTokens;
    protected final int totalTimeoutSeconds;

    private OpenAIClient client;
    private String clientKey;

    public OpenAiProvider(ApiKeyStore keys,
                          @Value("${fieldarchive.nlquery.openai.model:gpt-6-astra}") String model,
                          @Value("${fieldarchive.nlquery.max-output-tokens:16000}") long maxOutputTokens,
                          @Value("${fieldarchive.nlquery.total-timeout-seconds:180}") int totalTimeoutSeconds) {
        this.keys = keys;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.totalTimeoutSeconds = totalTimeoutSeconds;
    }

    @Override public String id() { return "openai"; }
    @Override public String label() { return "ChatGPT (OpenAI)"; }
    @Override public String model() { return model; }
    @Override public ContextBudget contextBudget() { return ContextBudget.FULL; }
    @Override public int totalTimeoutSeconds() { return totalTimeoutSeconds; }

    /** The local variant: no strict schemas, final answer through a tool, no reasoning options. */
    protected boolean local() { return false; }

    protected String agentName() { return "ChatGPT"; }

    @Override
    public ProviderStatusDto status() {
        ProviderStatusDto s = new ProviderStatusDto();
        s.id = id();
        s.label = label();
        s.model = model;
        s.free = false;
        s.keySource = keys.keySource(id());
        s.available = s.keySource != null;
        s.reason = s.available ? null : "No OpenAI API key";
        return s;
    }

    protected synchronized OpenAIClient client() {
        String key = keys.key(id()).orElseThrow(() -> new BadRequestException("ChatGPT: no OpenAI API key is set."));
        if (client == null || !key.equals(clientKey)) {
            client = OpenAIOkHttpClient.builder().apiKey(key).timeout(Duration.ofMinutes(5)).maxRetries(2).build();
            clientKey = key;
        }
        return client;
    }

    @Override
    public void validateKey(String apiKey) {
        try {
            OpenAIOkHttpClient.builder().apiKey(apiKey).build().models().list();
        } catch (RuntimeException e) {
            throw readable(e);
        }
    }

    protected BadRequestException readable(RuntimeException e) {
        String name = agentName();
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            return new BadRequestException(name + ": the API key was refused. Check it, or try another agent.");
        }
        if (e instanceof RateLimitException) {
            return new BadRequestException(name + ": rate limit or quota reached (on OpenAI this is also the error of an account without credit). Try again later, or try another agent.");
        }
        if (e instanceof OpenAIServiceException se) {
            if (se.statusCode() >= 500) return new BadRequestException(name + ": the service is unavailable right now. Try again, or try another agent.");
            return new BadRequestException(name + ": the request was rejected (" + se.statusCode() + "): " + se.getMessage());
        }
        if (e instanceof OpenAIIoException) {
            return new BadRequestException(name + ": cannot reach the service (network or timeout). Try again, or try another agent.");
        }
        return new BadRequestException(name + ": " + e.getMessage());
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, JsonValue> asJsonValues(JsonNode node) {
        Map<String, Object> map = JSON.convertValue(node, Map.class);
        Map<String, JsonValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, JsonValue.from(v)));
        return out;
    }

    private FunctionTool functionTool(String name, String description, JsonNode schema) {
        FunctionTool.Builder b = FunctionTool.builder()
                .name(name)
                .description(description)
                .parameters(FunctionTool.Parameters.builder().putAllAdditionalProperties(asJsonValues(schema)).build());
        b.strict(!local());
        return b.build();
    }

    @Override
    public AgentSession start(AgentRequest request) {
        List<FunctionTool> tools = new ArrayList<>();
        for (ToolSpec spec : request.tools()) tools.add(functionTool(spec.name(), spec.description(), spec.parametersSchema()));
        if (local()) {
            // Structured output is not guaranteed through Ollama's compatibility API: the final answer is a tool call.
            tools.add(functionTool(SUBMIT_ANSWER,
                    "Give your final answer. Call this exactly once, when you are done looking things up.",
                    request.outputSchema()));
        }
        String instructions = request.staticSystem() + "\n\n" + request.projectSystem();
        List<ResponseInputItem> input = new ArrayList<>();
        input.add(userItem(request.userMessage()));
        OpenAIClient c = client();

        ResponseTextConfig textConfig = local() ? null : ResponseTextConfig.builder()
                .format(ResponseFormatTextJsonSchemaConfig.builder()
                        .name("nl_query_answer")
                        .schema(ResponseFormatTextJsonSchemaConfig.Schema.builder()
                                .putAllAdditionalProperties(asJsonValues(request.outputSchema())).build())
                        .strict(true)
                        .build())
                .build();

        return new AgentSession() {
            @Override
            public AgentTurn next() {
                ResponseCreateParams.Builder b = ResponseCreateParams.builder()
                        .model(model)
                        .instructions(instructions)
                        .inputOfResponse(input)
                        .maxOutputTokens(maxOutputTokens)
                        .parallelToolCalls(true);
                tools.forEach(b::addTool);
                if (!local()) {
                    b.text(textConfig)
                            .reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build())
                            .store(false)
                            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
                            .promptCacheKey(request.cacheKey());
                }
                Response response;
                try {
                    response = c.responses().create(b.build());
                } catch (RuntimeException e) {
                    throw readable(e);
                }

                List<ToolCall> calls = new ArrayList<>();
                String submitted = null;
                StringBuilder text = new StringBuilder();
                boolean refused = false;
                for (ResponseOutputItem item : response.output()) {
                    // Append every item unchanged (reasoning included), the transcript is re-sent in full.
                    if (item.isReasoning()) {
                        input.add(ResponseInputItem.ofReasoning(item.reasoning().get()));
                    } else if (item.isFunctionCall()) {
                        ResponseFunctionToolCall fc = item.functionCall().get();
                        input.add(ResponseInputItem.ofFunctionCall(fc));
                        if (local() && SUBMIT_ANSWER.equals(fc.name())) submitted = fc.arguments();
                        else calls.add(new ToolCall(fc.callId(), fc.name(), fc.arguments()));
                    } else if (item.isMessage()) {
                        ResponseOutputMessage m = item.message().get();
                        input.add(ResponseInputItem.ofResponseOutputMessage(m));
                        for (ResponseOutputMessage.Content part : m.content()) {
                            part.outputText().ifPresent(t -> text.append(t.text()));
                            if (part.refusal().isPresent()) refused = true;
                        }
                    }
                }
                String usage = response.usage().map(u -> "in=" + u.inputTokens() + " out=" + u.outputTokens()
                        + " cached=" + u.inputTokensDetails().cachedTokens()).orElse("");

                if (submitted != null) return new AgentTurn(Kind.FINAL, List.of(), submitted, usage);
                if (!calls.isEmpty()) return new AgentTurn(Kind.TOOL_CALLS, calls, null, usage);
                if (refused) return new AgentTurn(Kind.REFUSED, List.of(), null, usage);
                if (response.status().map(ResponseStatus.INCOMPLETE::equals).orElse(false)) {
                    return new AgentTurn(Kind.TRUNCATED, List.of(), null, usage);
                }
                return new AgentTurn(Kind.FINAL, List.of(), text.toString(), usage);
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                for (ToolResult r : results) {
                    String output = r.isError() ? errorJson(r.content()) : r.content();
                    input.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                            .callId(r.callId())
                            .output(output)
                            .build()));
                }
            }

            @Override
            public void addUserText(String text) {
                input.add(userItem(text));
            }
        };
    }

    private static String errorJson(String message) {
        try {
            return JSON.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"tool failed\"}";
        }
    }

    private static ResponseInputItem userItem(String text) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(text)
                .build());
    }
}
