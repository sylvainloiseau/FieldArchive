package fr.cnrs.lacito.fieldarchive.services.nlquery.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.*;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnrs.lacito.fieldarchive.dtos.ProviderStatusDto;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.nlquery.ApiKeyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** Claude, through the Anthropic Messages API. */
@Component
public class ClaudeProvider implements LlmProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiKeyStore keys;
    private final String model;
    private final long maxOutputTokens;
    private final int totalTimeoutSeconds;
    private final boolean fallbacks;

    private AnthropicClient client;
    private String clientKey;

    public ClaudeProvider(ApiKeyStore keys,
                          @Value("${fieldarchive.nlquery.claude.model:claude-opus-5-5}") String model,
                          @Value("${fieldarchive.nlquery.max-output-tokens:16000}") long maxOutputTokens,
                          @Value("${fieldarchive.nlquery.total-timeout-seconds:180}") int totalTimeoutSeconds,
                          @Value("${fieldarchive.nlquery.claude.fallbacks:true}") boolean fallbacks) {
        this.keys = keys;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.totalTimeoutSeconds = totalTimeoutSeconds;
        this.fallbacks = fallbacks;
    }

    @Override public String id() { return "claude"; }
    @Override public String label() { return "Claude (Anthropic)"; }
    @Override public String model() { return model; }
    @Override public ContextBudget contextBudget() { return ContextBudget.FULL; }
    @Override public int totalTimeoutSeconds() { return totalTimeoutSeconds; }

    @Override
    public ProviderStatusDto status() {
        ProviderStatusDto s = new ProviderStatusDto();
        s.id = id();
        s.label = label();
        s.model = model;
        s.free = false;
        s.keySource = keys.keySource(id());
        s.available = s.keySource != null;
        s.reason = s.available ? null : "No Anthropic API key";
        return s;
    }

    private synchronized AnthropicClient client() {
        String key = keys.key(id()).orElseThrow(() -> new BadRequestException("Claude: no Anthropic API key is set."));
        if (client == null || !key.equals(clientKey)) {
            client = build(key);
            clientKey = key;
        }
        return client;
    }

    private static AnthropicClient build(String key) {
        return AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofMinutes(5)).maxRetries(2).build();
    }

    @Override
    public void validateKey(String apiKey) {
        try {
            build(apiKey).models().list();
        } catch (RuntimeException e) {
            throw readable(e);
        }
    }

    static BadRequestException readable(RuntimeException e) {
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            return new BadRequestException("Claude: the API key was refused. Check it, or try another agent.");
        }
        if (e instanceof RateLimitException) {
            return new BadRequestException("Claude: rate limit or quota reached. Wait a moment, or try another agent.");
        }
        if (e instanceof AnthropicServiceException se) {
            if (se.statusCode() >= 500) return new BadRequestException("Claude: the service is unavailable right now. Try again, or try another agent.");
            return new BadRequestException("Claude: the request was rejected (" + se.statusCode() + "): " + se.getMessage());
        }
        if (e instanceof AnthropicIoException) {
            return new BadRequestException("Claude: cannot reach the Anthropic API (network). Check the connection, or try another agent.");
        }
        return new BadRequestException("Claude: " + e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, JsonValue> asJsonValues(JsonNode node) {
        Map<String, Object> map = JSON.convertValue(node, Map.class);
        Map<String, JsonValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, JsonValue.from(v)));
        return out;
    }

    @Override
    public AgentSession start(AgentRequest request) {
        List<Tool> tools = new ArrayList<>();
        for (ToolSpec spec : request.tools()) {
            JsonNode schema = spec.parametersSchema();
            List<String> required = new ArrayList<>();
            schema.path("required").forEach(r -> required.add(r.asText()));
            Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
            asJsonValues(schema.path("properties")).forEach(props::putAdditionalProperty);
            tools.add(Tool.builder()
                    .name(spec.name())
                    .description(spec.description())
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(props.build())
                            .required(required)
                            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                            .build())
                    .strict(true)
                    .build());
        }
        OutputConfig outputConfig = OutputConfig.builder()
                .effort(OutputConfig.Effort.MEDIUM)
                .format(JsonOutputFormat.builder()
                        .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(asJsonValues(request.outputSchema())).build())
                        .build())
                .build();
        List<TextBlockParam> system = List.of(
                TextBlockParam.builder().text(request.staticSystem()).cacheControl(CacheControlEphemeral.builder().build()).build(),
                TextBlockParam.builder().text(request.projectSystem()).build());

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(request.userMessage()).build());
        AnthropicClient c = client();

        return new AgentSession() {
            @Override
            public AgentTurn next() {
                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(maxOutputTokens)
                        .systemOfTextBlockParams(system)
                        .outputConfig(outputConfig)
                        .messages(messages);
                tools.forEach(params::addTool);
                if (fallbacks) {
                    // Server-side refusal fallback: a declined request is re-run on a fallback model.
                    params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
                    params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
                }
                Message response;
                try {
                    response = c.messages().create(params.build());
                } catch (RuntimeException e) {
                    throw readable(e);
                }
                // Append unchanged: the thinking blocks must be passed back as they came.
                messages.add(response.toParam());

                Usage u = response.usage();
                String usage = "in=" + u.inputTokens() + " out=" + u.outputTokens()
                        + " cacheRead=" + u.cacheReadInputTokens().orElse(0L)
                        + " cacheWrite=" + u.cacheCreationInputTokens().orElse(0L);

                StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
                if (StopReason.REFUSAL.equals(stop)) return new AgentTurn(Kind.REFUSED, List.of(), null, usage);
                if (StopReason.MAX_TOKENS.equals(stop) || StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stop)
                        || StopReason.PAUSE_TURN.equals(stop)) {
                    return new AgentTurn(Kind.TRUNCATED, List.of(), null, usage);
                }

                List<ToolCall> calls = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : response.content()) {
                    block.toolUse().ifPresent(tu -> {
                        String args;
                        try {
                            args = JSON.writeValueAsString(tu._input().convert(Object.class));
                        } catch (Exception ex) {
                            args = "{}";
                        }
                        calls.add(new ToolCall(tu.id(), tu.name(), args));
                    });
                    block.text().ifPresent(t -> text.append(t.text()));
                }
                if (StopReason.TOOL_USE.equals(stop) && !calls.isEmpty()) {
                    return new AgentTurn(Kind.TOOL_CALLS, calls, null, usage);
                }
                return new AgentTurn(Kind.FINAL, List.of(), text.toString(), usage);
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                List<ContentBlockParam> blocks = new ArrayList<>();
                for (ToolResult r : results) {
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(r.callId())
                            .content(r.content())
                            .isError(r.isError())
                            .build()));
                }
                // All results of one turn in a single user message.
                messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build());
            }

            @Override
            public void addUserText(String text) {
                messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(text).build());
            }
        };
    }
}
