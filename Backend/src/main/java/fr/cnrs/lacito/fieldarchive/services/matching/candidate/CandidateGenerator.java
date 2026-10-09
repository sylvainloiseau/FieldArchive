package fr.cnrs.lacito.fieldarchive.services.matching.candidate;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingRunSummaryDto;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfiguration;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.EntityProfile;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.EntityProfiles;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.TypeGroupResolver;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.ValueResolver;

import java.util.*;
import java.util.function.Consumer;

/**
 * Enumerates the pairs to score for a pair of data sources: entities already matched in the
 * other source are skipped, pairs are generated inside each type group (with blocking keys on
 * large groups), and a pair is emitted once, under the group chosen by
 * {@link TypeGroupResolver#sharedGroup}, unless an exclusion applies. Counts go to the summary.
 */
public final class CandidateGenerator {

    private final MatchingConfiguration configuration;
    private final TypeGroupResolver types;
    private final ValueResolver values;

    public CandidateGenerator(MatchingConfiguration configuration, TypeGroupResolver types, ValueResolver values) {
        this.configuration = configuration;
        this.types = types;
        this.values = values;
    }

    public void generateCandidatePairs(EntityProfiles first, EntityProfiles second, MatchingExclusions exclusions,
                                       MatchingRunSummaryDto summary, Consumer<CandidatePair> sink) {
        List<EntityProfile> eligibleA = eligible(first, second.dataSource(), exclusions, summary, true);
        List<EntityProfile> eligibleB = eligible(second, first.dataSource(), exclusions, summary, false);

        Map<TypeGroup, List<EntityProfile>> byGroupB = new LinkedHashMap<>();
        for (EntityProfile b : eligibleB) {
            for (TypeGroup g : types.groupsOf(b.types())) byGroupB.computeIfAbsent(g, k -> new ArrayList<>()).add(b);
        }

        for (TypeGroup g : configuration.groups()) {
            List<EntityProfile> groupA = new ArrayList<>();
            for (EntityProfile a : eligibleA) if (types.groupsOf(a.types()).contains(g)) groupA.add(a);
            List<EntityProfile> groupB = byGroupB.getOrDefault(g, List.of());
            if (groupA.isEmpty() || groupB.isEmpty()) continue;
            summary.possiblePairCount += (long) groupA.size() * groupB.size();
            boolean blocking = BlockingIndex.applies(g)
                    && (long) groupA.size() * groupB.size() > configuration.defaults().blockingMinimumPairs();
            BlockingIndex index = blocking ? BlockingIndex.build(g, values, groupB) : null;
            for (EntityProfile a : groupA) {
                for (EntityProfile b : blocking ? index.candidatesFor(a) : groupB) {
                    Optional<TypeGroup> shared = types.sharedGroup(a.types(), b.types());
                    if (shared.isEmpty() || shared.get() != g) continue;   // emitted under another group
                    emit(a, b, g, exclusions, summary, sink);
                }
            }
        }

        if (configuration.defaults().compareUntypedEntities()) {
            for (EntityProfile a : eligibleA) {
                if (!a.isUntyped()) continue;
                for (EntityProfile b : eligibleB) {
                    if (b.isUntyped()) continue;
                    summary.possiblePairCount++;
                    types.sharedGroup(a.types(), b.types()).ifPresent(g -> emit(a, b, g, exclusions, summary, sink));
                }
            }
            for (EntityProfile b : eligibleB) {
                if (!b.isUntyped()) continue;
                for (EntityProfile a : eligibleA) {
                    if (a.isUntyped()) continue;
                    summary.possiblePairCount++;
                    types.sharedGroup(a.types(), b.types()).ifPresent(g -> emit(a, b, g, exclusions, summary, sink));
                }
            }
        }
    }

    private void emit(EntityProfile a, EntityProfile b, TypeGroup g, MatchingExclusions exclusions,
                      MatchingRunSummaryDto summary, Consumer<CandidatePair> sink) {
        Optional<MatchingExclusions.Reason> reason = exclusions.reasonFor(a, b);
        if (reason.isPresent()) {
            switch (reason.get()) {
                case REJECTED -> summary.excludedRejectedCount++;
                case INVARIANT -> summary.excludedInvariantCount++;
                default -> { }
            }
            return;
        }
        summary.comparedPairCount++;
        sink.accept(new CandidatePair(a, b, g));
    }

    private List<EntityProfile> eligible(EntityProfiles profiles, String otherSource, MatchingExclusions exclusions,
                                         MatchingRunSummaryDto summary, boolean isA) {
        List<EntityProfile> out = new ArrayList<>();
        for (EntityProfile p : profiles.all()) {
            if (p.isUntyped()) {
                if (isA) summary.untypedA++; else summary.untypedB++;
                if (!configuration.defaults().compareUntypedEntities()) continue;
            } else if (types.groupsOf(p.types()).isEmpty()) {
                if (isA) summary.notInAnyGroupA++; else summary.notInAnyGroupB++;
                continue;
            }
            if (exclusions.isMatchedIn(p, otherSource)) {
                if (isA) summary.excludedMatchedA++; else summary.excludedMatchedB++;
                continue;
            }
            out.add(p);
        }
        return out;
    }
}
