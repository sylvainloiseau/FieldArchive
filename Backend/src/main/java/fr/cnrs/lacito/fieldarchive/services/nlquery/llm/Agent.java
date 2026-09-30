package fr.cnrs.lacito.fieldarchive.services.nlquery.llm;

import fr.cnrs.lacito.fieldarchive.dtos.ProviderStatusDto;

/** Everything the agent drop-down lists: the three LLM providers and the built-in agent. */
public interface Agent {
    String id();       // claude | openai | local | builtin
    String label();
    String model();
    ProviderStatusDto status();
}
