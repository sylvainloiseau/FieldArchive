package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies of the {@code /matching} endpoints (the stored shapes,
 * {@link MatchLink} and {@link MatchingRunSummaryDto}, have their own files).
 */
public final class MatchingDtos {

    private MatchingDtos() {}

    // =========================
    //  Runs and overview
    // =========================

    /** One pair of sources, or every pair when {@code all} is true. */
    public static class MatchingRunRequest {
        public String dataSourceA;
        public String dataSourceB;
        public Boolean all;
    }

    public static class DataSourceSummaryDto {
        public String shortName;
        public String longName;
        /** internal | external */
        public String type;
        public int entityCount;
    }

    public static class PairOverviewDto {
        public String key;
        public String dataSourceA;
        public String dataSourceB;
        /** PROPOSED, ACCEPTED, REJECTED, MANUAL, INFERRED, toReview, superseded, orphaned → count */
        public Map<String, Integer> counts = new LinkedHashMap<>();
        public MatchingRunSummaryDto lastRun;
        public boolean stale;
        /** neverRun | dataChanged | configurationChanged | decisionsRevoked */
        public List<String> staleReasons = new ArrayList<>();
    }

    public static class MatchingOverviewDto {
        public List<DataSourceSummaryDto> dataSources = new ArrayList<>();
        public List<PairOverviewDto> pairs = new ArrayList<>();
        public List<ConflictDto> conflicts = new ArrayList<>();
        public String configurationOrigin;
        public String configurationHash;
        /** Set when the configuration cannot be used (the settings page must fix it). */
        public String configurationError;
        /** Set when data_matching.json was unreadable and set aside. */
        public String fileWarning;
    }

    // =========================
    //  Links
    // =========================

    public static class MatchLinkPageDto {
        public int total;
        public List<MatchLinkDto> items = new ArrayList<>();
    }

    public enum LinkAction { ACCEPT, REJECT, UNDO }

    public static class LinkActionRequest {
        public LinkAction action;
        public String note;
    }

    public static class BulkLinkActionRequest {
        public List<String> ids = new ArrayList<>();
        public LinkAction action;
    }

    public static class NoteRequest {
        public String note;
    }

    public static class CreateManualMatchRequest {
        public EntityNodeDto a;
        public EntityNodeDto b;
        public String note;
    }

    /** The effect of a change; with {@code applied = false} it was a dry run. */
    public static class LinkChangeResultDto {
        public List<MatchLinkDto> links = new ArrayList<>();
        public List<MatchLinkDto> inferredAdded = new ArrayList<>();
        public List<MatchLinkDto> inferredRemoved = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public boolean applied;
    }

    // =========================
    //  Comparison, search, clusters
    // =========================

    public static class PropertyValuesDto {
        public String predicate;
        public String curie;
        public List<String> values = new ArrayList<>();
    }

    /** One entity with the statements of its own graph. */
    public static class EntitySideDto {
        public EntityNodeDto node;
        public List<PropertyValuesDto> properties = new ArrayList<>();
    }

    public static class PairComparisonDto {
        public EntitySideDto a;
        public EntitySideDto b;
        public String typeGroupId;
        public String typeGroupLabel;
        public Double score;
        public Double comparedWeight;
        public boolean proposable;
        /** PROPOSABLE | INSUFFICIENT_EVIDENCE | BELOW_THRESHOLD | NO_SHARED_GROUP */
        public String outcome;
        public List<ConceptEvidenceDto> evidence = new ArrayList<>();
        /** Concepts compared, as "concept" → [predicates of A, predicates of B] (for aligned display). */
        public Map<String, List<List<String>>> conceptPredicates = new LinkedHashMap<>();
        public MatchLinkDto existingLink;
        /** Why these two entities cannot be linked, if they cannot. */
        public List<String> blockingReasons = new ArrayList<>();
    }

    public static class MatchableEntityDto {
        public EntityNodeDto node;
        public List<String> typeCuries = new ArrayList<>();
        public List<EntityNodeDto> matchedWith = new ArrayList<>();
    }

    public static class MatchClusterDto {
        public String id;
        public List<EntityNodeDto> nodes = new ArrayList<>();
        public List<MatchLinkDto> links = new ArrayList<>();
        public boolean conflict;
    }

    // =========================
    //  Settings page
    // =========================

    public static class TypeCandidateDto {
        public String iri;
        public String curie;
        public String label;
        public String ontologyNamespace;
        public boolean defined;
        public Map<String, Long> usage = new LinkedHashMap<>();
    }

    public static class PredicateCandidateDto {
        public String iri;
        public String curie;
        public String label;
        public boolean defined;
        /** "iri", "literal:<datatype curie>", or "unknown" */
        public String kind;
        public Map<String, Long> usage = new LinkedHashMap<>();
        public List<String> sampleValues = new ArrayList<>();
        public Double averageLength;
        public String suggestedComparator;
    }

    public static class CoverageRequest {
        public TypeGroupDto group;
        public Map<String, String> prefixes;
        public Boolean includeSubclasses;
    }

    public static class ConceptCoverageDto {
        public long entitiesWithValue;
        public double percent;
    }

    public static class SourceCoverageDto {
        public String dataSource;
        public long entityCount;
        public Map<String, ConceptCoverageDto> concepts = new LinkedHashMap<>();
    }

    public static class GroupCoverageDto {
        public List<SourceCoverageDto> sources = new ArrayList<>();
    }

    public static class MatchingPreviewRequest {
        public MatchingConfigurationDto configuration;
        public String dataSourceA;
        public String dataSourceB;
    }

    public static class PreviewSampleDto {
        public EntityNodeDto a;
        public EntityNodeDto b;
        public double score;
        public String typeGroupLabel;
        public MatchStatus status;
    }

    public static class ProposalDiffDto {
        public int added;
        public int removed;
        public int unchanged;
        public List<PreviewSampleDto> samplesAdded = new ArrayList<>();
        public List<PreviewSampleDto> samplesRemoved = new ArrayList<>();
    }

    public static class DecisionAgreementDto {
        /** Confirmed links that the configuration would propose / would not find. */
        public int confirmedAbove;
        public int confirmedBelow;
        /** Rejected links that the configuration would propose again / would not propose. */
        public int rejectedAbove;
        public int rejectedBelow;
        public List<PreviewSampleDto> samples = new ArrayList<>();
    }

    public static class MatchingPreviewDto {
        public MatchingRunSummaryDto summary;
        public ProposalDiffDto proposalDiff;
        public DecisionAgreementDto decisionAgreement;
        /** 20 bins over [0, 1] of every scored candidate pair. */
        public int[] scoreHistogram = new int[20];
        public ValidationReportDto validation;
    }
}
