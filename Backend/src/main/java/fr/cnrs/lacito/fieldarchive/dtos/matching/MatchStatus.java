package fr.cnrs.lacito.fieldarchive.dtos.matching;

/** Status of a stored link between two entities of different data sources. */
public enum MatchStatus {
    /** Computed by a run, not reviewed. */
    PROPOSED,
    /** A proposal accepted by the user. */
    ACCEPTED,
    /** The two entities are different; never proposed again. */
    REJECTED,
    /** Linked by hand. */
    MANUAL,
    /** Implied by confirmed links through other data sources; maintained by the backend only. */
    INFERRED;

    /** Accepted or manual: a link the user asserted. */
    public boolean isConfirmed() {
        return this == ACCEPTED || this == MANUAL;
    }
}
