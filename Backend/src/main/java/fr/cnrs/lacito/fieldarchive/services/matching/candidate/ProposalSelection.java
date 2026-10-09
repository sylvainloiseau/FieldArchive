package fr.cnrs.lacito.fieldarchive.services.matching.candidate;

import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.PairScore;

import java.util.*;

/** Keeps, for every entity, its best proposals only. */
public final class ProposalSelection {

    public static final Comparator<PairScore> BEST_FIRST = Comparator
            .comparingDouble(PairScore::score).reversed()
            .thenComparing(s -> s.pair().a().iri())
            .thenComparing(s -> s.pair().b().iri());

    private ProposalSelection() {}

    /**
     * A pair survives when it is among the {@code maxPerEntity} best pairs of either of its
     * entities. Deterministic: ties are broken by the IRIs. The result is sorted best first.
     */
    public static List<PairScore> keepBestProposalsPerEntity(List<PairScore> scored, int maxPerEntity) {
        List<PairScore> sorted = new ArrayList<>(scored);
        sorted.sort(BEST_FIRST);
        Map<EntityNode, Integer> rank = new HashMap<>();
        List<PairScore> out = new ArrayList<>();
        for (PairScore s : sorted) {
            int ra = rank.merge(s.pair().a().node(), 1, Integer::sum);
            int rb = rank.merge(s.pair().b().node(), 1, Integer::sum);
            if (ra <= maxPerEntity || rb <= maxPerEntity) out.add(s);
        }
        return out;
    }
}
