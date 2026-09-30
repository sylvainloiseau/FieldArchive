package fr.cnrs.lacito.fieldarchive.services.nlquery.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * An LLM agent driven by the provider-neutral loop of {@code NlQueryService}. Implementations
 * are the only classes that import an SDK.
 */
public interface LlmProvider extends Agent {

    enum ContextBudget { FULL, COMPACT }

    ContextBudget contextBudget();

    /** Maximum duration, in seconds, of a whole translation with this provider. */
    int totalTimeoutSeconds();

    AgentSession start(AgentRequest request);

    /** For providers that need an API key: check it with a cheap authenticated call; throws a readable error when refused. */
    default void validateKey(String apiKey) {
        throw new UnsupportedOperationException(id() + " does not use an API key");
    }

    record ToolSpec(String name, String description, JsonNode parametersSchema) {}

    record AgentRequest(String staticSystem, String projectSystem, String userMessage,
                        List<ToolSpec> tools, JsonNode outputSchema, String cacheKey) {}

    record ToolCall(String id, String name, String argumentsJson) {}

    record ToolResult(String callId, String content, boolean isError) {}

    enum Kind { TOOL_CALLS, FINAL, REFUSED, TRUNCATED }

    record AgentTurn(Kind kind, List<ToolCall> toolCalls, String finalJson, String usage) {}

    /** Holds the provider-native transcript, so reasoning state is kept between turns. */
    interface AgentSession {
        AgentTurn next();
        void addToolResults(List<ToolResult> results);
        void addUserText(String text);
    }
}
