package fr.cnrs.lacito.fieldarchive.services.matching.profile;

import fr.cnrs.lacito.fieldarchive.services.matching.scoring.DateInterval;

import java.util.Optional;

/** Reads what a linked entity stands for: its text (name, textual value) and its date. */
public interface LinkedValueLookup {

    Optional<String> textOf(String iri);

    Optional<DateInterval> dateOf(String iri);

    /** A lookup that knows nothing (linked entities are then compared by IRI only). */
    LinkedValueLookup NONE = new LinkedValueLookup() {
        @Override
        public Optional<String> textOf(String iri) {
            return Optional.empty();
        }

        @Override
        public Optional<DateInterval> dateOf(String iri) {
            return Optional.empty();
        }
    };
}
