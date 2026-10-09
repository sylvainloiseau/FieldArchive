/**
 * Data matching: links between IRIs of different data sources that denote the same entity.
 * Mirrors the backend DTOs of /matching (dtos/matching/*).
 */

export type MatchStatus = 'PROPOSED' | 'ACCEPTED' | 'REJECTED' | 'MANUAL' | 'INFERRED';
export type LinkOrigin = 'ALGORITHM' | 'USER' | 'INFERENCE';
export type LinkAction = 'ACCEPT' | 'REJECT' | 'UNDO';

export interface EntityNode {
  dataSource: string;
  iri: string;
  label?: string | null;
  types?: string[];
}

export interface ConceptEvidence {
  concept: string;
  comparator: string;
  weight: number;
  score: number;
  valueA: string;
  valueB: string;
}

export interface InferenceStep {
  kind: 'link' | 'identical';
  linkId?: string | null;
  status?: MatchStatus | null;
  from: EntityNode;
  to: EntityNode;
}

export interface MatchLink {
  id: string;
  a: EntityNode;
  b: EntityNode;
  status: MatchStatus;
  origin: LinkOrigin;
  typeGroupId: string | null;
  typeGroupLabel: string | null;
  score: number | null;
  comparedWeight: number | null;
  evidence: ConceptEvidence[];
  inferredFrom: string[];
  proposedAt: string | null;
  decidedAt: string | null;
  note: string | null;
  // computed when read
  superseded: boolean;
  orphanA: boolean;
  orphanB: boolean;
  typeMismatch: boolean;
  conflict: boolean;
  inferenceSteps: InferenceStep[];
}

export interface MatchLinkPage {
  total: number;
  items: MatchLink[];
}

export interface MatchingRunSummary {
  dataSourceA: string;
  dataSourceB: string;
  startedAt: string;
  durationMs: number;
  algorithmVersion: number;
  configurationHash: string;
  entityCountA: number;
  entityCountB: number;
  notInAnyGroupA: number;
  notInAnyGroupB: number;
  untypedA: number;
  untypedB: number;
  excludedMatchedA: number;
  excludedMatchedB: number;
  identicalIriCount: number;
  possiblePairCount: number;
  comparedPairCount: number;
  excludedRejectedCount: number;
  excludedInvariantCount: number;
  belowThresholdCount: number;
  insufficientEvidenceCount: number;
  proposalCount: number;
  proposalCountByGroup: Record<string, number>;
}

export interface DataSourceSummary {
  shortName: string;
  longName: string;
  type: 'internal' | 'external';
  entityCount: number;
}

export type StaleReason = 'neverRun' | 'dataChanged' | 'configurationChanged' | 'decisionsRevoked';

export interface PairOverview {
  key: string;
  dataSourceA: string;
  dataSourceB: string;
  counts: Record<string, number>;
  lastRun: MatchingRunSummary | null;
  stale: boolean;
  staleReasons: StaleReason[];
}

export interface Conflict {
  kind: 'SAME_SOURCE_IN_CLUSTER' | 'REJECTED_INSIDE_CLUSTER';
  nodes: EntityNode[];
  linkIds: string[];
  message: string;
}

export interface MatchingOverview {
  dataSources: DataSourceSummary[];
  pairs: PairOverview[];
  conflicts: Conflict[];
  configurationOrigin: 'project' | 'default' | null;
  configurationHash: string | null;
  configurationError: string | null;
  fileWarning: string | null;
}

export interface LinkChangeResult {
  links: MatchLink[];
  inferredAdded: MatchLink[];
  inferredRemoved: MatchLink[];
  warnings: string[];
  applied: boolean;
}

export interface PropertyValues {
  predicate: string;
  curie: string;
  values: string[];
}

export interface EntitySide {
  node: EntityNode;
  properties: PropertyValues[];
}

export interface PairComparison {
  a: EntitySide;
  b: EntitySide;
  typeGroupId: string | null;
  typeGroupLabel: string | null;
  score: number | null;
  comparedWeight: number | null;
  proposable: boolean;
  outcome: 'PROPOSABLE' | 'INSUFFICIENT_EVIDENCE' | 'BELOW_THRESHOLD' | 'NO_SHARED_GROUP' | null;
  evidence: ConceptEvidence[];
  conceptPredicates: Record<string, string[][]>;
  existingLink: MatchLink | null;
  blockingReasons: string[];
}

export interface MatchableEntity {
  node: EntityNode;
  typeCuries: string[];
  matchedWith: EntityNode[];
}

export interface MatchCluster {
  id: string;
  nodes: EntityNode[];
  links: MatchLink[];
  conflict: boolean;
}

// =========================
//  Configuration (settings page)
// =========================

export type ComparatorName = 'auto' | 'string' | 'name' | 'text' | 'exact' | 'date' | 'number' | 'linkedEntity';

export interface MatchingConcept {
  predicates: string[];
  comparator: ComparatorName;
  weight: number;
  blocking: boolean;
  options: Record<string, number | boolean>;
}

export interface TypeGroup {
  id: string;
  groupLabel: string;
  note?: string | null;
  equivalentTypes: string[];
  threshold?: number | null;
  minimumComparedWeight?: number | null;
  requiredConcepts: string[];
  properties: Record<string, MatchingConcept>;
}

export interface MatchingDefaults {
  threshold: number;
  minimumComparedWeight: number;
  maxProposalsPerEntity: number;
  compareUntypedEntities: boolean;
  includeSubclasses: boolean;
  blockingMinimumPairs: number;
}

export interface MatchingConfiguration {
  formatVersion: number;
  prefixes: Record<string, string>;
  defaults: MatchingDefaults;
  equivalentGroups: TypeGroup[];
}

export interface ValidationIssue {
  path: string;
  code: string;
  message: string;
}

export interface ValidationReport {
  errors: ValidationIssue[];
  warnings: ValidationIssue[];
}

export interface MatchingConfigurationEnvelope {
  configuration: MatchingConfiguration;
  origin: 'project' | 'default' | 'import';
  hash: string;
  validation: ValidationReport;
}

export interface TypeCandidate {
  iri: string;
  curie: string;
  label: string | null;
  ontologyNamespace: string | null;
  defined: boolean;
  usage: Record<string, number>;
}

export interface PredicateCandidate {
  iri: string;
  curie: string;
  label: string | null;
  defined: boolean;
  kind: string;
  usage: Record<string, number>;
  sampleValues: string[];
  averageLength: number | null;
  suggestedComparator: ComparatorName;
}

export interface SourceCoverage {
  dataSource: string;
  entityCount: number;
  concepts: Partial<Record<string, { entitiesWithValue: number; percent: number }>>;
}

export interface GroupCoverage {
  sources: SourceCoverage[];
}

export interface PreviewSample {
  a: EntityNode;
  b: EntityNode;
  score: number;
  typeGroupLabel: string | null;
  status: MatchStatus | null;
}

export interface MatchingPreview {
  summary: MatchingRunSummary;
  proposalDiff: {
    added: number; removed: number; unchanged: number;
    samplesAdded: PreviewSample[]; samplesRemoved: PreviewSample[];
  };
  decisionAgreement: {
    confirmedAbove: number; confirmedBelow: number;
    rejectedAbove: number; rejectedBelow: number;
    samples: PreviewSample[];
  };
  scoreHistogram: number[];
  validation: ValidationReport;
}

/** One line of explanation per comparator, for the settings page. */
export const COMPARATOR_HELP: Record<ComparatorName, string> = {
  auto: 'Chosen from each value: dates, numbers, linked entities, else text.',
  string: 'Text similarity (accents, case and punctuation ignored).',
  name: 'Like text, but word order is ignored: "Dupont, Jean" = "Jean Dupont".',
  text: 'Shared words, for long descriptions.',
  exact: 'Equal or not (identifiers, e-mails, gender…).',
  date: 'Same day = 100 %, overlapping dates (e.g. a year and a day in it) = 85 %, else 0.',
  number: 'Equal within a tolerance.',
  linkedEntity: 'Same linked entity, or similar names of the linked entities.'
};

export const STALE_REASON_LABELS: Record<StaleReason, string> = {
  neverRun: 'never run',
  dataChanged: 'a source changed since the last run',
  configurationChanged: 'the settings changed since the last run',
  decisionsRevoked: 'links were undone since the last run'
};

export function nodeName(n: EntityNode | null | undefined): string {
  if (!n) return '';
  return n.label || n.iri;
}

/** Well-known prefixes, usable in CURIEs even when the configuration does not declare them (as in the backend). */
export const IMPLICIT_PREFIXES: Record<string, string> = {
  rdf: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
  rdfs: 'http://www.w3.org/2000/01/rdf-schema#',
  owl: 'http://www.w3.org/2002/07/owl#',
  xsd: 'http://www.w3.org/2001/XMLSchema#',
  dcterms: 'http://purl.org/dc/terms/',
  skos: 'http://www.w3.org/2004/02/skos/core#',
  foaf: 'http://xmlns.com/foaf/0.1/',
  rico: 'https://www.ica.org/standards/RiC/ontology#'
};

export function isAbsoluteIri(s: string): boolean {
  return s.includes('://') || s.startsWith('urn:');
}

export function expandCurie(s: string, prefixes: Record<string, string>): string {
  const v = s.trim();
  if (isAbsoluteIri(v)) return v;
  const i = v.indexOf(':');
  if (i <= 0) return v;
  const ns = prefixes[v.substring(0, i)] ?? IMPLICIT_PREFIXES[v.substring(0, i)];
  return ns ? ns + v.substring(i + 1) : v;
}

export function compactIri(iri: string, prefixes: Record<string, string>): string {
  let best: [string, string] | null = null;
  for (const [p, ns] of Object.entries({ ...IMPLICIT_PREFIXES, ...prefixes })) {
    if (ns && iri.startsWith(ns) && (!best || ns.length > best[1].length)) {
      const local = iri.substring(ns.length);
      if (local && !local.includes('/') && !local.includes('#')) best = [p, ns];
    }
  }
  return best ? `${best[0]}:${iri.substring(best[1].length)}` : iri;
}
