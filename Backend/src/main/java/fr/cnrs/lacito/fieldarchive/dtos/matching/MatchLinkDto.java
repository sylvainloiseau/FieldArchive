package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.List;

/** A stored link plus the flags computed when it is read. */
public class MatchLinkDto extends MatchLink {
    /** PROPOSED only: one of its entities already has a counterpart, or accepting would break an invariant. */
    public boolean superseded;
    public boolean orphanA;
    public boolean orphanB;
    /** Confirmed links only: the two entities share no type group. */
    public boolean typeMismatch;
    /** The link is part of a reported conflict. */
    public boolean conflict;
    /** INFERRED only: the path, step by step. */
    public List<InferenceStepDto> inferenceSteps = new ArrayList<>();

    public static class InferenceStepDto {
        /** "link" or "identical". */
        public String kind;
        public String linkId;
        public MatchStatus status;
        public EntityNodeDto from;
        public EntityNodeDto to;
    }
}
