package fr.cnrs.lacito.fieldarchive.dtos.matching;

/** Who created a link record. */
public enum LinkOrigin {
    /** A matching run (the record starts as PROPOSED). */
    ALGORITHM,
    /** The user, from the manual link dialog. */
    USER,
    /** The reconciliation of inferred links. */
    INFERENCE
}
