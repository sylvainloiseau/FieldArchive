package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.dtos.matching.ConceptEvidenceDto;
import fr.cnrs.lacito.fieldarchive.services.matching.candidate.CandidatePair;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConcept;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.ValueResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Scores a candidate pair: for each concept of the group, the best score over the values of
 * both entities; then the weighted mean over the concepts that could be compared. A concept
 * missing on either side is neutral (it neither helps nor hurts), which is why the evidence
 * rules ({@code requiredConcepts}, {@code minimumComparedWeight}) exist.
 */
public final class PairScorer {

    private final ValueResolver resolver;
    private final ComparatorRegistry comparators;
    private final BiPredicate<String, String> sameEntity;

    public PairScorer(ValueResolver resolver, ComparatorRegistry comparators, BiPredicate<String, String> sameEntity) {
        this.resolver = resolver;
        this.comparators = comparators;
        this.sameEntity = sameEntity;
    }

    public PairScore scorePair(CandidatePair pair) {
        TypeGroup group = pair.group();
        List<ConceptEvidenceDto> evidence = new ArrayList<>();
        Set<String> compared = new HashSet<>();
        double weighted = 0, comparedWeight = 0;
        for (MatchingConcept concept : group.concepts()) {
            List<ComparableValue> valuesA = resolver.resolve(pair.a(), concept);
            List<ComparableValue> valuesB = resolver.resolve(pair.b(), concept);
            if (valuesA.isEmpty() || valuesB.isEmpty()) continue;
            ComparisonContext ctx = new ComparisonContext(concept, sameEntity);
            Optional<MultiValueComparison.Best> best =
                    MultiValueComparison.bestOver(valuesA, valuesB, comparators.of(concept.comparator()), ctx);
            if (best.isEmpty()) continue;
            ConceptEvidenceDto e = new ConceptEvidenceDto();
            e.concept = concept.key();
            e.comparator = concept.comparator().jsonName();
            e.weight = concept.weight();
            e.score = round(best.get().score());
            e.valueA = best.get().valueA().display();
            e.valueB = best.get().valueB().display();
            evidence.add(e);
            compared.add(concept.key());
            weighted += best.get().score() * concept.weight();
            comparedWeight += concept.weight();
        }
        double score = comparedWeight == 0 ? 0 : weighted / comparedWeight;
        boolean requiredCovered = compared.containsAll(group.requiredConcepts());
        return new PairScore(pair, group, round(score), comparedWeight, evidence, requiredCovered);
    }

    public static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
