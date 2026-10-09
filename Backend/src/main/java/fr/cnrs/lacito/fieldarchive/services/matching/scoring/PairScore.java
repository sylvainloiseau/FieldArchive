package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.dtos.matching.ConceptEvidenceDto;
import fr.cnrs.lacito.fieldarchive.services.matching.candidate.CandidatePair;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;

import java.util.List;

/** The score of a candidate pair and the evidence it is made of. */
public record PairScore(CandidatePair pair,
                        TypeGroup group,
                        double score,
                        double comparedWeight,
                        List<ConceptEvidenceDto> evidence,
                        boolean requiredConceptsCovered) {

    public enum Outcome { PROPOSABLE, INSUFFICIENT_EVIDENCE, BELOW_THRESHOLD }

    public Outcome outcome() {
        if (!requiredConceptsCovered || comparedWeight < group.minimumComparedWeight() || comparedWeight == 0) {
            return Outcome.INSUFFICIENT_EVIDENCE;
        }
        return score >= group.threshold() ? Outcome.PROPOSABLE : Outcome.BELOW_THRESHOLD;
    }

    public boolean isProposable() {
        return outcome() == Outcome.PROPOSABLE;
    }
}
