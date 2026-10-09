package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingRunSummaryDto;
import fr.cnrs.lacito.fieldarchive.services.matching.candidate.CandidateGenerator;
import fr.cnrs.lacito.fieldarchive.services.matching.candidate.MatchingExclusions;
import fr.cnrs.lacito.fieldarchive.services.matching.candidate.ProposalSelection;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.DataSourcePair;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.MatchClusters;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfiguration;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.EntityProfiles;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.TypeGroupResolver;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.ValueResolver;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.ComparatorRegistry;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.PairScore;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.PairScorer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.Set;

/**
 * The matching algorithm on already loaded profiles, without any storage: exclusions,
 * candidate generation, scoring, selection of the best proposals. Used by a run and by the
 * configuration preview.
 */
public final class MatchingEngine {

    public static final int ALGORITHM_VERSION = 1;

    /** @param scores every scored candidate (for the preview's histogram) */
    public record Result(List<PairScore> proposals, List<PairScore> scores, MatchingRunSummaryDto summary) {}

    private final MatchingConfiguration configuration;
    private final TypeGroupResolver types;
    private final ValueResolver values;
    private final ComparatorRegistry comparators = new ComparatorRegistry();

    public MatchingEngine(MatchingConfiguration configuration, Function<String, Set<String>> expandWithSubtypes,
                          ValueResolver values) {
        this.configuration = configuration;
        this.types = new TypeGroupResolver(configuration, expandWithSubtypes);
        this.values = values;
    }

    public TypeGroupResolver types() {
        return types;
    }

    public PairScorer scorer(MatchClusters clusters) {
        return new PairScorer(values, comparators, clusters::sameEntity);
    }

    /**
     * @param first  profiles of {@code pair.first()}
     * @param second profiles of {@code pair.second()}
     */
    public Result run(DataSourcePair pair, EntityProfiles first, EntityProfiles second,
                      Collection<? extends MatchLink> links, MatchClusters clusters, int identicalIriCount) {
        MatchingRunSummaryDto summary = new MatchingRunSummaryDto();
        summary.dataSourceA = pair.first();
        summary.dataSourceB = pair.second();
        summary.algorithmVersion = ALGORITHM_VERSION;
        summary.configurationHash = configuration.hash();
        summary.entityCountA = first.size();
        summary.entityCountB = second.size();
        summary.identicalIriCount = identicalIriCount;

        MatchingExclusions exclusions = MatchingExclusions.forPair(pair, links, clusters);
        PairScorer scorer = scorer(clusters);
        List<PairScore> scores = new ArrayList<>();
        List<PairScore> proposable = new ArrayList<>();
        new CandidateGenerator(configuration, types, values).generateCandidatePairs(first, second, exclusions, summary, candidate -> {
            PairScore s = scorer.scorePair(candidate);
            scores.add(s);
            switch (s.outcome()) {
                case PROPOSABLE -> proposable.add(s);
                case BELOW_THRESHOLD -> summary.belowThresholdCount++;
                case INSUFFICIENT_EVIDENCE -> summary.insufficientEvidenceCount++;
            }
        });
        List<PairScore> proposals = ProposalSelection.keepBestProposalsPerEntity(
                proposable, configuration.defaults().maxProposalsPerEntity());
        summary.proposalCount = proposals.size();
        for (PairScore p : proposals) summary.proposalCountByGroup.merge(p.group().id(), 1, Integer::sum);
        return new Result(proposals, scores, summary);
    }
}
