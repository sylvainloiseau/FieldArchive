package fr.cnrs.lacito.fieldarchive.services.nlquery.llm;

import fr.cnrs.lacito.fieldarchive.dtos.NlClarification;

import java.util.List;

/** An agent that answers without the LLM loop (the built-in, pattern-based agent). */
public interface DirectAgent extends Agent {

    /** The same answer shape an LLM gives, plus the route used (for the "lookups" trace). */
    record DirectAnswer(ModelOutput output, List<String> lookups, List<String> examples) {}

    DirectAnswer translate(String question, List<NlClarification> clarifications);
}
