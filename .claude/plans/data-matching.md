# Data matching across data sources — plan

Goal: find, review and remember the IRIs that refer to the same real-world entity in different
data sources of a project (record linkage). The algorithm proposes candidate pairs with a score;
the user accepts or rejects them, or links two entities by hand; links implied by transitivity
are recorded as *inferred*. Everything is stored in a JSON file in the project folder and
mirrored as `owl:sameAs` triples in a dedicated named graph.

> **Status (2026-10-09): implemented** (phases 1–6). Differences from this plan:
> - `lastScore` (§4.10, §6.2) was dropped: decided pairs are excluded from runs, so they are never rescored.
> - The settings page merges some components: the defaults form and the group list live in `matching-settings`, the concept table in `matching-group-editor`, the JSON view in the page itself.
> - The validator does not warn about a required concept with 0 % coverage (§5.3); the coverage column shows it in red instead.
> - No frontend spec was added.
> - Value checked: "Dupont, Jean" vs "Jean Dupont" has an indel ratio of 0.545 once punctuation is removed (0.522 with the comma).

Decisions taken with the user (2026-10-08):

| # | Decision |
|---|---|
| D1 | Matching is **1-to-1 within a pair of data sources**: an IRI of A has at most one counterpart in B. |
| D2 | "Already matched" is scoped **per pair of sources**: an IRI matched in B can still be matched in C. |
| D3 | Confirmed links are written as **`owl:sameAs` in a dedicated named graph**, included in the project backup. |
| D4 | The algorithm configuration is **per project and editable in the GUI**, on a settings page opened from the data-source page. |
| D5 | **No duplicates inside one source**: a source is never matched against itself. As a consequence, two distinct IRIs of the same source can never be linked, directly or by transitivity. |
| D6 | A fourth status **`INFERRED`**: if a≡b (A–B) and b≡c (B–C) are confirmed, then (a, c) is `INFERRED` for the pair (A, C), and neither a nor c is searched for another counterpart in that pair. |
| D7 | The CSV format of the notebook is not kept: entities are read directly from the RDF store. The CSVs serve only to build a test fixture. |

Contents: 1 audit · 2 vocabulary · 3 links, statuses, clusters · 4 algorithm · 5 configuration ·
6 persistence · 7 `owl:sameAs` graph · 8 backend design · 9 REST · 10 matching page ·
11 settings page · 12 tests · 13 phases · 14 later.

---

## 1. Audit of the notebook (`Data Matching/Data_Matching.ipynb`)

### 1.1 What it does

1. `ENTITY_EQUIVALENCES`: groups of equivalent classes (FOAF ↔ RiC-O), and for each group a map
   *concept → predicates*.
2. Reads two long CSVs (`entity, property, value, typevalue`) and pivots each into one row per
   entity, with lists for multi-valued properties and `<prop>_type` ∈ `string|date|number|iri`.
3. Drops the IRIs present in both sources.
4. Reads the decisions stored as properties of the source entities (`matchedWith`,
   `matchStatus` ∈ `confirmed|manual|rejected`). It drops resolved source entities and their
   targets, and builds `rejected_pairs`.
5. For every (source, target) pair: skips rejected pairs, checks type compatibility, then
   compares each source column with the same column of the target.
   - The comparator follows `<col>_type`: `fuzz.ratio` for strings; partial-date overlap for
     dates (100 exact / 85 overlap / 0 disjoint); equality within a tolerance for numbers.
   - For multi-valued properties, it keeps the best score over all value combinations.
   - `score = Σ score·w / Σ w over compared columns`, so a missing value is neutral. Pairs with
     `score ≥ 75` are kept.

The core ideas are kept: per-concept comparators, best over multiple values, a weighted mean
that ignores missing values, a type gate, and exclusion of resolved and rejected pairs.

### 1.2 Issues and their fixes

| # | Issue | Consequence | Fix |
|---|---|---|---|
| A1 | The concept → predicates map is **never used for scoring**. Columns are raw predicates of the source, looked up under the same predicate in the target. | `foaf:name` is never compared with `rico:name`. In the samples, *Marie Lefebvre* / *Marie Lefevre* and *Musee d'Orsay* / *Musée d'Orsay* are missed. | Values are gathered **per concept** (union of its predicates) on both sides. |
| A2 | Columns and comparator types come from the source only. | Asymmetric: `score(a,b) ≠ score(b,a)`. | The comparator is declared per concept; `auto` inspects each value. Pairs of sources are unordered. |
| A3 | `[row.get("rdf:type")]` is `[None]`/`[nan]` for an untyped entity, which is not empty. | Untyped entities are always rejected, contrary to the intent. | Explicit option `compareUntypedEntities` (default `false`). |
| A4 | Group membership needs `set(types) ⊆ group`. | `rico:Person` + any other class matches no group. | Membership by intersection, with subclass expansion. |
| A5 | No class hierarchy. | `rico:Person` vs `rico:Agent` fails. | Subclass expansion + "primary groups" rule (§4.4). |
| A6 | Uniform weights keyed by raw column. | `gender` weighs as much as `name`. | Weight per concept. |
| A7 | No minimum evidence. | Samples: `item9`/`P003` score 100 on `rdf:num` alone; `item8`/`P002` score 85 on a birth year alone. | `requiredConcepts` and `minimumComparedWeight`. |
| A8 | Decisions are stored as single-valued properties of the source entity, inside its (read-only) graph. | One rejection per entity; overwritten by a sync. | One record per pair in a JSON file (§6). |
| A9 | `rejected_pairs` is a global variable. | Hidden coupling. | An explicit `MatchingExclusions` object. |
| A10 | Exhaustive n×m loop. | Too slow beyond a few thousand entities. | Partition by type group + blocking keys (§4.5). |
| A11 | No limit per entity. | Dozens of proposals for a common name. | Top-K per entity. |
| A12 | Lowercase only: no accent folding, punctuation or token order. | *Dupont, Jean* vs *Jean Dupont* = 0.52 (0.545 once punctuation is removed). | `TextNormalizer.fold` + a `name` comparator with token-sort (→ 1.0). |
| A13 | IRIs compared as strings. RiC-O mixes datatype and object properties for the same concept (`rico:name` literal vs `rico:hasOrHadName` → `rico:Name` entity; `rico:birthDate` vs `rico:hasBirthDate` → `rico:Date`). | Meaningless scores; literal-vs-entity comparisons are impossible. | A value-resolution step (§4.6): an IRI is resolved to its text or date before a string/date comparison. A `linkedEntity` comparator handles identity. |
| A14 | Narrow date parsing; unparsable dates. | `xsd:dateTime` and RiC-O dates are ignored. | `PartialDate` parser (§4.7). Unparsable = not comparable. |
| A15 | Identical IRIs dropped silently. | Invisible. | They are reported and glue clusters together (§3.3). |
| A16 | `prefixes` declared but unused. | Verbose JSON. | CURIEs are accepted and written (§5). |
| A17 | `print` in the inner loop. | — | Summary logging only. |
| A18 | Sample data: `item3` (`rico:Place`) is *confirmed* with `P001` (`foaf:Person`). | A confirmed link with incompatible types. | Confirmed links are never type-checked (the user may know better), but the UI shows a "type mismatch" warning. |

Configuration content to review while migrating (not blocking):
- `workplaceHomepage` is mapped to `rico:hasOrHadEmployer`: different meanings in one concept.
- The Agent group has `birthday`.
- `knows` mixes `foaf:knows` and `rico:knowsOf`.

The migrated default flags these concepts with a low weight.

---

## 2. Vocabulary used in the code

| Term | Meaning | Java |
|---|---|---|
| Node | An IRI *as it appears in one data source*: `(dataSource, iri)`. | `EntityNode` (record) |
| Data-source pair | An unordered pair of two distinct data sources; canonical order = shortNames sorted (`String.compareTo`). | `DataSourcePair(first, second)`, `key()` = `first + "⇄" + second` |
| Entity profile | A node with its types, label and values grouped by concept, read from its graph only. | `EntityProfile` |
| Type group | One entry of `equivalentGroups`. | `TypeGroup` |
| Concept | One entry of a group's `properties`. | `MatchingConcept` |
| Candidate pair | Two profiles (one per source) that passed exclusions and the type gate. | `CandidatePair` |
| Evidence | The result of comparing one concept. | `ConceptEvidence` |
| Link | A stored pair of nodes with a status. | `MatchLink` |
| Confirmed link | A link with status `ACCEPTED` or `MANUAL`. | `MatchStatus.isConfirmed()` |
| Cluster | A connected component of nodes under confirmed links + identical IRIs. | `MatchCluster` |

---

## 3. Links, statuses and clusters

### 3.1 Statuses

| Status | Created by | Meaning | Confirmed? | Excludes its nodes from new proposals in this pair? |
|---|---|---|---|---|
| `PROPOSED` | a run | Candidate above the threshold, not reviewed | no | no |
| `ACCEPTED` | user, on a `PROPOSED` | Same entity | **yes** | yes |
| `REJECTED` | user | Different entities; never proposed again | no | only this pair of nodes |
| `MANUAL` | user, from the manual dialog | Same entity, linked by hand | **yes** | yes |
| `INFERRED` | the reconciliation (§3.4) | Same entity by transitivity | derived | yes |

`origin` records who created the record: `ALGORITHM` (a run), `USER` (manual dialog) or
`INFERENCE`.

### 3.2 Transitions

```
            accept                         reject
PROPOSED ─────────▶ ACCEPTED   PROPOSED ─────────▶ REJECTED
    ▲   ◀─────────────┘ undo        ▲   ◀────────────┘ undo
    │                                       ACCEPTED ──reject──▶ REJECTED (direct)
(new manual link on the pair)               REJECTED ──accept──▶ ACCEPTED (direct)
    ↓
 PROPOSED ──"link manually"──▶ ACCEPTED   (a manual link on a pair that is PROPOSED = accept it)
 REJECTED ──"link manually"──▶ MANUAL     (an explicit user decision overrides the rejection)
 (no link) ──"link manually"─▶ MANUAL
 MANUAL   ──undo──▶ (record deleted)       MANUAL ──reject──▶ REJECTED (origin USER)
 REJECTED with origin USER ──undo──▶ (record deleted)
 INFERRED: never changed by the user; created and deleted only by reconciliation.
```

Undo means: back to `PROPOSED` when `origin = ALGORITHM`, deletion when `origin = USER`.

### 3.3 Clusters

The graph whose vertices are nodes and whose edges are:
- every confirmed link;
- **identical IRIs**: `(A, x)` — `(B, x)` when the IRI `x` has statements in both graphs.

Each connected component is a `MatchCluster`, computed with union-find.

**Invariants** checked before every user write. A write that would break them is refused with
`409 Conflict`, and the message explains the chain.

- **I1 (D5)**: a cluster contains at most one IRI per data source. Two nodes of the same source in
  a cluster may only be the same IRI (impossible by construction).
- **I2**: no `REJECTED` link has both nodes in the same cluster. Accepting a link that would merge
  clusters across a rejection is refused. The UI offers "undo the rejection, then accept".
- **I3**: for every two nodes of a cluster in different sources, there is exactly one link
  record, which is `ACCEPTED`, `MANUAL` or `INFERRED`. The reconciliation maintains this.
- **I4**: at most one link record per unordered pair of nodes.

Identical IRIs and syncs can break I1/I2 without any user action, for example when a re-synced
source introduces an IRI already confirmed elsewhere. These are reported as **conflicts**
(`MatchClusters.conflicts()`), shown in a banner, and never auto-repaired.

### 3.4 Reconciliation of inferred links

`MatchingService.reconcileInferredLinks()` runs after every change to a confirmed link, after
removing orphans, after a project opens, and after a backup is restored:

1. Build the clusters (§3.3).
2. For every cluster and every two nodes `(A,a)`, `(C,c)` with `A ≠ C` and `a ≠ c`, with no
   `ACCEPTED`/`MANUAL` link between them:
   - if an `INFERRED` link exists, refresh its `inferredFrom`;
   - else if a `PROPOSED` link exists, turn it into `INFERRED` (keep score and evidence; set
     `origin` to `INFERENCE`);
   - else if a `REJECTED` link exists, it is a conflict (I2): leave it and report it;
   - else create an `INFERRED` link.
3. Every `INFERRED` link whose two nodes are no longer in the same cluster is deleted. If it was
   a former proposal, its evidence is lost; a rerun recreates it.
4. `inferredFrom` = the link ids along the shortest path between the two nodes in the cluster
   (BFS over the confirmed links and identical-IRI edges, which are recorded as
   `"identical:<iri>"`). The UI shows it as a chain:
   `item2 ≡ P002 (accepted) ≡ A1 (manual)`.
5. Rewrite the `owl:sameAs` graph (§7) if any confirmed or inferred link changed.

### 3.5 Derived flags (computed on read, never stored)

- `superseded` (on `PROPOSED`): in pair (A, B), node a already has a counterpart in B in its
  cluster (or b in A), or accepting it would break I1/I2. It is hidden from *To review*, shown
  in *All* with a badge, and the accept button is disabled. It reappears if the blocking link is
  undone.
- `orphanA` / `orphanB`: the IRI no longer has any statement in its source graph, or the source
  no longer exists.
- `typeMismatch` (confirmed links): the nodes share no type group (A18).
- Pair `stale` (§6.3).

---

## 4. The algorithm

### 4.1 Overview

```
runMatching(dataSourceA, dataSourceB)                       // MatchingService, under the matching lock
  1. pair          = DataSourcePair.of(dataSourceA, dataSourceB)       // 400 if equal or unknown
  2. configuration = configurationService.effectiveConfiguration()
  3. profilesA     = profileLoader.loadProfiles(pair.first(),  configuration)
     profilesB     = profileLoader.loadProfiles(pair.second(), configuration)
  4. clusters      = matchStore.clusters()                              // includes identical IRIs
  5. exclusions    = MatchingExclusions.forPair(pair, matchStore.links(), clusters)
  6. candidates    = candidateGenerator.generateCandidatePairs(profilesA, profilesB, exclusions, configuration)
  7. scored        = candidates.map(pairScorer::scorePair).filter(PairScore::isProposable)
  8. proposals     = ProposalSelection.keepBestProposalsPerEntity(scored, configuration)
  9. guard: the open project is still the one of step 2 (as in NlQueryService), else abort
 10. matchStore.replaceProposals(pair, proposals, runSummary)          // PROPOSED of this pair only
 11. return runSummary
```

`runMatchingForAllDataSourcePairs()` runs every unordered pair in canonical order. A run is
synchronous in v1; progress goes to a `MatchingProgressListener` (no-op for now), so an async
version can be added later (§14).

### 4.2 Run summary (`MatchingRunSummaryDto`, also stored, §6)

`dataSourceA`, `dataSourceB`, `startedAt`, `durationMs`, `algorithmVersion` (a constant, `1`),
`configurationHash`, `entityCountA`/`B`, `notInAnyGroupA`/`B`, `untypedA`/`B`,
`excludedMatchedA`/`B` (already have a counterpart in the other source), `identicalIriCount`,
`possiblePairCount` (Σ over groups of |A_g|×|B_g|), `comparedPairCount`,
`excludedRejectedCount`, `belowThresholdCount`, `insufficientEvidenceCount`, `proposalCount`,
`proposalCountByGroup`.

### 4.3 Step 3: loading profiles (`EntityProfileLoader`)

- The set of predicates to read = every predicate of every concept of the configuration +
  `rdf:type` + `RdfEntityService.LABEL_PREDICATES`.
- One query per graph:
  `SELECT ?s ?p ?o WHERE { GRAPH <g> { ?s ?p ?o } VALUES ?p { … } FILTER(isIRI(?s)) }`.
  Blank-node subjects are ignored: they cannot be linked durably.
- Subjects with no `rdf:type` in **this** graph are untyped for this source. A subject with
  neither a type nor a value of a configured predicate is not loaded.
- `EntityProfile`: `node`, `types: Set<String>`, `label` (the first LABEL_PREDICATE value in this
  graph, else `bestLabel`), `valuesByPredicate: Map<String, List<Value>>`.
  `valuesOf(concept)` is the concatenation over the concept's predicates, deduplicated, in
  configuration order.
- `EntityProfiles`: profiles of one source, indexed by IRI and by type group (§4.4).

### 4.4 The type gate (`TypeGroupResolver`)

- `expandedTypes(G)` = ⋃ `ontologyService.expandWithSubtypes(t)` for t ∈ `G.equivalentTypes`
  when `includeSubclasses`, else the declared types. The hierarchy known is the one parsed from
  the DefinedOntologies, per ontology.
- `groupsOf(entity)` = { G | `expandedTypes(G)` ∩ `entity.types` ≠ ∅ }.
- Group order: `G1 ⊑ G2` iff `expandedTypes(G1) ⊆ expandedTypes(G2)` (e.g. Person ⊑ Agent).
- `primaryGroups(entity)` = the minimal elements of `groupsOf(entity)` for ⊑.
- **Pair rule**: (a, b) is compared under the group
  `G* = most specific of { G ∈ groupsOf(a) ∩ groupsOf(b) | G ∈ primaryGroups(a) ∪ primaryGroups(b) }`.
  - "Most specific" = smallest `expandedTypes`, ties broken by configuration order.
  - If the set is empty, the pair is not compared.

  | a | b | groups(a) ∩ groups(b) | primaries | G* |
  |---|---|---|---|---|
  | `rico:Person` | `foaf:Person` | Person, Agent | Person / Person | Person |
  | `rico:Person` | `rico:Agent` | Agent | Person / Agent | Agent |
  | `rico:Person` | `foaf:Organization` | Agent | Person / Organization | — (not compared) |
  | `rico:Person` + `rico:Place` | `rico:Place` | Place | Person, Place / Place | Place |

- Untyped entities, or entities in no group:
  - if `compareUntypedEntities` is false (default), they are skipped and counted;
  - else an untyped entity is compared with every typed entity of the other source under that
    entity's most specific primary group, and two untyped entities are not compared (no concept
    list applies).

### 4.5 Step 6: candidates (`MatchingExclusions`, `BlockingIndex`, `CandidateGenerator`)

**Exclusions for pair (A, B)**, checked in this order and counted in the summary:

1. a's cluster contains a node of B (a is already matched for this pair, D1/D6). This covers
   `ACCEPTED`, `MANUAL`, `INFERRED` and identical IRIs. The same holds for b and A.
2. The IRI of a equals the IRI of b (identical IRIs, already covered by 1).
3. (a, b) is `REJECTED`.
4. Merging cluster(a) and cluster(b) would break I1 (both contain a node of the same third source
   C with different IRIs) or I2.
5. The type gate gives no G*.

**Generation**: for each group G, iterate over the A profiles whose primary groups include G or
a subgroup of G. With blocking (below), each is compared only with the B profiles sharing a
key; otherwise with all B profiles of G. The pair rule of §4.4 is applied, and duplicates are
removed with a `Set<pairKey>`. Result: `Stream<CandidatePair>`.

**Blocking** (`BlockingIndex`) applies when some concepts of G have `"blocking": true`:
- Keys of a profile: for `name`/`string` concepts, every folded token of ≥ 3 characters plus
  its first 4 characters; for `exact`, the normalised value; for `date`, the year.
- An A profile is compared with the B profiles sharing ≥ 1 key. Profiles with no key on a
  blocking concept are compared with all B profiles of G (no silent loss).
- Blocking is used only when |A_g|×|B_g| > `blockingMinimumPairs` (default 250 000). Below that,
  the exhaustive comparison is cheap and exact.

### 4.6 Value resolution (`LinkedValueResolver`, per-run cache)

Before comparison, every value becomes a `ComparableValue` (`kind`, `text`, `date`, `number`,
`iri`). The resolution depends on the concept's comparator:

| Comparator | Literal | IRI |
|---|---|---|
| `string`, `name`, `text`, `exact` | lexical form | `textOf(iri)` = first value of `LABEL_PREDICATES`, then `rico:textualValue`, `rico:expressedDate`, across all data graphs; none → value dropped |
| `date` | `PartialDate.parse(literal)` | `rico:normalizedDateValue`, else `rico:expressedDate`, else `rico:beginningDate`/`rico:endDate` as an interval; parsed |
| `number` | numeric value if parsable | dropped |
| `linkedEntity` | lexical form, used as a label | the IRI + `textOf(iri)` |
| `auto` | xsd date types → `date`; numeric types → `number`; else → `string` | → `linkedEntity` |

`auto` is decided **per pair of values**: two values of different kinds are not comparable.

### 4.7 Comparators (`scoring/`)

Interface: `OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx)`.
An empty result means "not comparable". `ctx` gives the clusters and the concept options. All
comparators are pure and registered in `ComparatorRegistry` by name.

| Name | Score | Options (default) |
|---|---|---|
| `string` | Indel ratio on normalised text: `norm(s)` = `TextNormalizer.fold`, punctuation → space, whitespace collapsed, trimmed; `ratio = 1 − (|a|+|b|−2·LCS(a,b)) / (|a|+|b|)` (= `fuzz.ratio`/100 with python-Levenshtein); two empty strings → not comparable | — |
| `name` | `max(ratio(a,b), ratio(tokenSort(a), tokenSort(b)))`; tokenSort = tokens of `TextNormalizer.tokens`, sorted, joined with a space | — |
| `text` | `TextNormalizer.jaccard(contentWords(a), contentWords(b))`; for strings > 200 characters, `string` falls back to this | — |
| `exact` | `norm(a).equals(norm(b))` → 1, else 0 | `caseSensitive` (false) |
| `date` | Intervals equal and both precise to the day → 1; overlapping → `partialOverlapScore`; disjoint → 0 | `partialOverlapScore` (0.85) |
| `number` | `|a−b| ≤ tolerance` → 1, else 0 | `tolerance` (0) |
| `linkedEntity` | Same IRI, or same cluster → 1; else `labelFactor × name(textA, textB)`; no text → not comparable | `labelFactor` (0.9) |

`PartialDate.parse` → `Optional<DateInterval(start, end, dayPrecise)>` accepts `YYYY`, `YYYY-MM`,
`YYYY-MM-DD`, an `xsd:dateTime` (date part), `xsd:gYear`, `xsd:gYearMonth` and `xsd:date`, with
an optional timezone (ignored), and an EDTF/ISO interval `YYYY[-MM[-DD]]/YYYY[-MM[-DD]]`.
Anything else → empty. Expressed dates like "ca. 1950" are left out in v1.

`StringSimilarity` (in `utils/`): `indelRatio(String, String)` with a two-row LCS DP, and
`tokenSortRatio`. No new dependency.

### 4.8 Step 7: scoring (`PairScorer`)

```java
PairScore scorePair(CandidatePair pair) {
    TypeGroup group = pair.group();
    List<ConceptEvidence> evidence = new ArrayList<>();
    for (MatchingConcept concept : group.concepts()) {
        List<ComparableValue> valuesA = resolver.resolve(pair.a(), concept);
        List<ComparableValue> valuesB = resolver.resolve(pair.b(), concept);
        if (valuesA.isEmpty() || valuesB.isEmpty()) continue;               // missing ≠ mismatch
        MultiValueComparison.bestOver(valuesA, valuesB, comparators.of(concept), ctx)
            .ifPresent(best -> evidence.add(new ConceptEvidence(concept.key(), concept.comparator(),
                    concept.weight(), best.score(), best.valueA().display(), best.valueB().display())));
    }
    double comparedWeight = sum(evidence, ConceptEvidence::weight);
    double score = comparedWeight == 0 ? 0 : sum(evidence, e -> e.score() * e.weight()) / comparedWeight;
    return new PairScore(pair, group, score, comparedWeight, evidence);
}

PairScore.isProposable() = evidence covers every group.requiredConcepts
                        && comparedWeight >= group.minimumComparedWeight
                        && score >= group.threshold
```

- `MultiValueComparison.bestOver` returns the maximum over all value combinations, and stops
  early at 1.0. To bound the cost, it uses at most 20 values per side, in a deterministic order.
- The scores are symmetric by construction: `scorePair(a,b) == scorePair(b,a)` (tested).
- The score is rounded to 4 decimals when stored.

### 4.9 Step 8: proposal selection (`ProposalSelection`)

For each profile on either side, keep its `maxProposalsPerEntity` best pairs. A pair survives if
it is in the top-K of **either** of its nodes. Ties are broken by (score desc, iriA, iriB), so
the selection is deterministic.

### 4.10 Rerun semantics

- `replaceProposals(pair, …)` deletes the `PROPOSED` links of this pair, then inserts the new
  ones. A new proposal is skipped if a link already exists for the same nodes (I4).
- Links with any other status are never touched.
- Decided links keep the score and evidence of when they were decided. If such a pair is
  recomputed, `lastScore` is updated: the pair is not excluded from scoring when only the link
  itself is confirmed, but it is not re-proposed.

---

## 5. Configuration

### 5.1 Files and lifecycle

- **Bundled default**: `Backend/src/main/resources/matching/default-matching-configuration.json`,
  migrated from the notebook's `ENTITY_EQUIVALENCES` (§5.4).
- **Project configuration**: `<project dir>/matching-configuration.json`. While absent, the
  effective configuration is the default. The first save from the GUI creates it. "Reset to
  default" deletes it.
- It is included in backups (§6.4).
- `configurationHash` = SHA-256 of the canonical serialisation of the effective configuration
  (Jackson, sorted keys, full IRIs). Comparing it with the run's hash makes a pair *stale*.
- Cached in memory per project, and reloaded when the file's mtime changes, so a hand edit is
  picked up without a restart.

### 5.2 Format (`formatVersion: 1`)

```jsonc
{
  "formatVersion": 1,
  "prefixes": { "foaf": "http://xmlns.com/foaf/0.1/", "rico": "https://www.ica.org/standards/RiC/ontology#",
                "rdfs": "http://www.w3.org/2000/01/rdf-schema#" },
  "defaults": {
    "threshold": 0.75,              // [0,1]
    "minimumComparedWeight": 2.0,   // ≥ 0
    "maxProposalsPerEntity": 5,     // 1..50
    "compareUntypedEntities": false,
    "includeSubclasses": true,
    "blockingMinimumPairs": 250000
  },
  "equivalentGroups": [
    {
      "id": "person",                       // slug, unique, immutable once saved
      "groupLabel": "Person",
      "note": "free text",
      "equivalentTypes": ["foaf:Person", "rico:Person"],
      "threshold": 0.8,                     // optional overrides of defaults: threshold, minimumComparedWeight
      "requiredConcepts": ["name"],
      "properties": {
        "name":      { "predicates": ["foaf:name", "rico:name", "rico:hasOrHadAgentName"],
                       "comparator": "name", "weight": 3, "blocking": true },
        "birthDate": { "predicates": ["foaf:birthday", "rico:birthDate", "rico:hasBirthDate"],
                       "comparator": "date", "weight": 2, "options": { "partialOverlapScore": 0.85 } },
        "knows":     ["foaf:knows", "rico:knows"]          // shorthand
      }
    }
  ]
}
```

Loading rules (`MatchingConfigurationLoader`):
- **Shorthand**: an array of predicates → `{ predicates, comparator: "auto", weight: 1, blocking: false }`.
- A missing `id` → slug of `groupLabel` (lowercase ASCII, non-alphanumerics → `-`), de-duplicated
  with `-2`, `-3`… This keeps the notebook JSON loadable as is.
- CURIEs are expanded with `prefixes`; full IRIs are accepted.
- Unknown fields are ignored on read and dropped on save.
- A `formatVersion` higher than supported → error (400 on PUT, default configuration + warning
  on read).

Saving rules (`MatchingConfigurationWriter`): the API carries **full IRIs only**. When writing
the file, IRIs are compacted to CURIEs when a declared prefix matches, so the file stays
readable for hand editing. The full form is always written: no shorthand, and `id` is always
present.

Java model (records, immutable): `MatchingConfiguration(formatVersion, prefixes, defaults,
groups)`, `MatchingDefaults(...)`, `TypeGroup(id, label, note, equivalentTypes, threshold?,
minimumComparedWeight?, requiredConcepts, concepts)`, `MatchingConcept(key, predicates,
comparator, weight, blocking, options)`. `TypeGroup.effectiveThreshold(defaults)` etc. resolve
the overrides. The concept order is the JSON order (`LinkedHashMap`).

### 5.3 Validation (`MatchingConfigurationValidator`)

Returns a `ValidationReportDto { errors: [Issue], warnings: [Issue] }` with
`Issue { path, code, message }` (e.g. `path = "equivalentGroups[2].properties.name.weight"`).
Errors block saving; warnings do not.

**Errors**
- `formatVersion` unsupported.
- Group: `id` missing, not matching `^[a-z0-9][a-z0-9-]*$`, or duplicated; `groupLabel` blank;
  `equivalentTypes` empty; `properties` empty; `requiredConcepts` naming an unknown concept.
- Concept: key not matching `^[A-Za-z][A-Za-z0-9_]*$`; `predicates` empty; unknown comparator;
  `weight` not in (0, 100]; options of the wrong type or range (`partialOverlapScore`,
  `labelFactor` ∈ [0,1]; `tolerance` ≥ 0); an option unknown to the comparator.
- An IRI that is neither absolute nor a CURIE with a declared prefix.
- Thresholds outside [0,1]; `minimumComparedWeight` < 0; `maxProposalsPerEntity` outside 1..50;
  `blockingMinimumPairs` < 0.

**Warnings**
- A type unknown to the DefinedOntologies **and** unused in the project's data.
- A predicate never used in the project's data.
- The same type declared in two groups.
- `minimumComparedWeight` > the sum of the group's weights (the group can never propose).
- A required concept with 0 % coverage in some data source.
- `blocking` on a `text` or `linkedEntity` concept (ignored).
- A changed group `id` when links still reference the old one.

### 5.4 Default configuration (migration of `ENTITY_EQUIVALENCES`)

The 8 groups are kept with their types and predicates. Comparators and weights follow these
rules, applied once when writing the default file:

| Concept (any group) | Comparator | Weight | Other |
|---|---|---|---|
| `name`, `placeName`, `accountName` | `name` | 3 | required in their group, blocking |
| `title` (Document group) | `string` | 3 | required, blocking |
| `firstName`, `familyName`, `nickname` | `name` | 1.5 | `familyName` blocking |
| `identifier`, `sha1`, `mailbox`, `phone`, `homepage`, `logo`, `depiction`, `thumbnail` | `exact` | 4 for `identifier`, `sha1`; 2 for the others | |
| `birthDate`, `deathDate`, `creationDate`, `birthday` | `date` | 2 | |
| `gender`, `status`, `age` | `exact` | 0.5 | |
| `generalDescription`, `history`, `scopeAndContent` | `text` | 1 | |
| every concept whose predicates are object properties (`knows`, `location`, `member`…) | `linkedEntity` | 1 | |
| `workplaceHomepage`, `myersBriggsType` | as above | 0.5 | flagged in `note` (§1.2) |

Groups without a natural name (`Image`, `Online Account` apart from `accountName`) get
`requiredConcepts: []` and rely on `minimumComparedWeight`. `rdfs:label` is added to every
`name`/`title` concept: it is one of the app's `LABEL_PREDICATES`.

---

## 6. Persistence: `<project dir>/data_matching.json`

### 6.1 Access

`MatchStore` uses the same mechanism as `NlQueryHistoryService`:
- path `projectsDirectory.getPublicPath().resolve(projectName).resolve("data_matching.json")`;
- write to `.tmp`, then `ATOMIC_MOVE`;
- an unreadable file is moved aside as `.corrupt-<millis>` and replaced by an empty one, with a
  warning in the overview.

Extract a generic `utils/ProjectJsonFile<T>` (read, write, raw bytes, restore) and migrate
`NlQueryHistoryService` onto it in the same change: three files now follow this pattern.

**Concurrency**: one `ReentrantLock` in `MatchingService` serialises every mutation (runs,
status changes, configuration saves, reconciliation, `owl:sameAs` rewrite). Reads take a
snapshot under the lock.

### 6.2 Format (`formatVersion: 1`)

```jsonc
{
  "formatVersion": 1,
  "confirmedLinksRevokedAt": "2026-10-08T10:30:00Z",   // last undo/reject/delete of a confirmed link (§6.3)
  "runs": {                                            // last run per pair, keyed by DataSourcePair.key()
    "gramps⇄tropy": { /* MatchingRunSummaryDto, §4.2 */ }
  },
  "links": [
    {
      "id": "6b1f0c3e-…",
      "a": { "dataSource": "gramps", "iri": "http://gramps.local/P002", "label": "Marie Lefevre", "types": ["http://xmlns.com/foaf/0.1/Person"] },
      "b": { "dataSource": "tropy",  "iri": "http://tropy.local/item2", "label": "Marie Lefebvre", "types": ["https://www.ica.org/standards/RiC/ontology#Person"] },
      "status": "ACCEPTED",                 // PROPOSED | ACCEPTED | REJECTED | MANUAL | INFERRED
      "origin": "ALGORITHM",                // ALGORITHM | USER | INFERENCE
      "typeGroupId": "person", "typeGroupLabel": "Person",
      "score": 0.9178, "comparedWeight": 5.0, "lastScore": 0.9178,
      "evidence": [
        { "concept": "name", "comparator": "name", "weight": 3, "score": 0.963, "valueA": "Marie Lefevre", "valueB": "Marie Lefebvre" },
        { "concept": "birthDate", "comparator": "date", "weight": 2, "score": 0.85, "valueA": "1948-11-02", "valueB": "1948" }
      ],
      "inferredFrom": [],                   // INFERRED only: link ids / "identical:<iri>" along the path
      "proposedAt": "…", "decidedAt": "…",  // decidedAt: last status change by the user
      "note": null
    }
  ]
}
```

- `a`/`b` follow the canonical order of their pair. The node identity is
  `(dataSource, iri)`.
- Label and type snapshots are refreshed at each run and each status change, while the entity
  exists. They let the UI show orphaned links.
- Manual links and rejections created by hand get the on-demand score and evidence
  (`comparePair`), with `typeGroupId = null` when there is no G*.

### 6.3 Staleness of a pair (computed on read)

A pair is `stale` with a list of reasons when:
- it was never run (`neverRun`);
- `app:lastSync` of an external source in the pair, or `dcterms:modified` of the internal data
  source (maintained by `InternalDataBookkeeping`), is later than `run.startedAt` (`dataChanged`);
- `configurationHash` differs (`configurationChanged`);
- `confirmedLinksRevokedAt` is later than `run.startedAt` (`decisionsRevoked`): entities that were
  excluded because of a link that no longer exists may now deserve proposals.

### 6.4 Backups and project lifecycle

- **`FileExportService.exportBackup`** adds `data_matching.json` and `matching-configuration.json`
  as zip entries next to `natural_language_query_history.json`, and adds `CTX_MATCHING` to the
  exported contexts (D3).
- **`FileImportService`** restores both files the same way. The restored graph is then
  overwritten by the regeneration at project open, so it always matches the JSON.
- Renaming a project moves its directory: nothing to do. DataSource shortNames are immutable
  (`updateDataSource` edits metadata only).
- **Deleting a data source** leaves its links in place (orphaned). The overview proposes
  "remove links of deleted source *x*", followed by a reconciliation.
- **Deleting an entity** (`RdfEntityService`) does not touch the file: its links become orphaned.

---

## 7. The `owl:sameAs` graph (D3)

- **IRI**: `RdfContexts.CTX_MATCHING = RdfNamespaces.APP + "/context/matching"`.
- **Content**: a full materialisation of the clusters. For each cluster, take its set of
  distinct IRIs (identical IRIs collapse into one). For every two distinct IRIs x, y, write
  `x owl:sameAs y` **and** `y owl:sameAs x`. This covers `ACCEPTED`, `MANUAL` and `INFERRED`
  links. The closure is complete, so a plain `?x owl:sameAs ?y` pattern finds every equivalent
  without property paths or reasoning. A cluster of k IRIs gives k(k−1) triples.
- **Orphaned links are included**: the JSON is the source of truth, and an orphan may come back
  at the next sync.
- **Writing**: `SameAsGraphWriter.rewrite(clusters)` clears the context and adds the triples in
  one transaction. It runs:
  - after every reconciliation that changed a confirmed or inferred link;
  - at project open/create. This needs a new Spring event `ProjectOpenedEvent(projectName)`,
    published by `ProjectService.openProject`/`createProject` after `ensureRicoLoaded`; it is an
    event rather than a direct call to avoid a new dependency cycle;
  - after a backup restore.
- It does **not** publish `ProjectDataChangedEvent`. The graph is derived data, not a project
  data change, and `UsageProfileService` only profiles data graphs.
- `InternalDataBookkeeping` is not involved: the graph is not the internal source.
- A SPARQL UPDATE typed by hand in the SPARQL page could write into this graph. Such a write is
  overwritten at the next regeneration; this is documented in the manual. `SparqlGuard` already
  restricts the natural-language agents to the internal graph.

Adjustments elsewhere:

| Where | Change |
|---|---|
| `RdfEntityService.getByIri` | Skip statements of `CTX_MATCHING` in the property loop. Add `RdfEntityDto.sameAs: List<SameAsDto {iri, label, datasourceShortNames}>` read from that graph. |
| `entity-details` | A read-only "Same as" chip row under the header: each chip = label + source badge; a click opens that entity with `EntityDetailsDialogService`. |
| `RdfEntityService.searchByLabel` | No change: the graph holds no label predicates. |
| `listWithoutType` | No change: matched IRIs are typed in their own graph. Untyped entities can only be matched when `compareUntypedEntities` is true, and are listed anyway. |
| `NlPromptBuilder` (graph layout) | Add: `<CTX_MATCHING> (read-only): owl:sameAs between IRIs of different sources that denote the same entity; use it to merge information across sources`. |
| `FileExportService.exportInternal` | No change (internal graph only). |

---

## 8. Backend design

### 8.1 Packages and classes

```
core/
  RdfContexts.CTX_MATCHING                         new constant
  ProjectOpenedEvent                               new record (projectName)
controllers/
  MatchingController                               /matching (§9)
  MatchingConfigurationController                  /matching/configuration (§9)
services/matching/
  MatchingService                  runs, link status changes, manual links, overview, compare, search, lock
  MatchStore                       data_matching.json (via ProjectJsonFile), link queries, replaceProposals
  MatchReconciler                  reconcileInferredLinks, invariant checks (checkConfirm(a, b) → violations)
  SameAsGraphWriter                rewrite(clusters)
  IdenticalIriFinder               IRIs present in ≥ 2 data graphs (cached; invalidated on ProjectDataChangedEvent)
  OrphanDetector                   existsInSource(node) (batched ASK/VALUES query, cached per request)
  cluster/
    EntityNode, DataSourcePair     records
    MatchClusters                  union-find; clusterOf(node), hasNodeIn(node, dataSource), path(n1, n2), conflicts()
  config/
    MatchingConfiguration, MatchingDefaults, TypeGroup, MatchingConcept   records
    MatchingConfigurationLoader    parse (shorthand, CURIEs, ids), canonical hash
    MatchingConfigurationWriter    compact to CURIEs, full form
    MatchingConfigurationValidator errors / warnings (§5.3)
    MatchingConfigurationService   effective config (project file or default), save, reset, mtime cache
    MatchingVocabularyService      types/predicates/coverage for the settings page (§11.4)
  profile/
    EntityProfile, EntityProfiles, EntityProfileLoader, TypeGroupResolver, LinkedValueResolver, ComparableValue
  candidate/
    CandidatePair, MatchingExclusions, BlockingIndex, CandidateGenerator, ProposalSelection
  scoring/
    ValueComparator, ComparatorRegistry, ComparisonContext,
    StringComparator, NameComparator, TextComparator, ExactComparator, DateComparator,
    NumberComparator, LinkedEntityComparator, AutoComparator,
    PartialDate, DateInterval, MultiValueComparison, ConceptEvidence, PairScore, PairScorer
  MatchingPreviewService           dry runs for the settings page (§11.5)
utils/
  StringSimilarity                 indelRatio, tokenSortRatio
  ProjectJsonFile<T>               generic JSON file next to the store
dtos/matching/                     DTOs and requests (§9)
```

Rules:
- Only `EntityProfileLoader`, `LinkedValueResolver`, `IdenticalIriFinder`, `OrphanDetector`,
  `SameAsGraphWriter` and `MatchingVocabularyService` touch RDF4J.
- `cluster/`, `scoring/`, `candidate/` and `config/` (apart from the service) are plain Java,
  unit-testable without Spring.
- Every public service method starts with `ProjectContext.requireProjectOpen()` (or the local
  equivalent).
- Dependencies: `MatchingService` → `DataSourceService`, `OntologyService`, `RdfEntityService`
  (labels), `ProjectsDirectory`. No service depends on `MatchingService`, so no new cycle.
- `MatchingService` listens to `ProjectDataChangedEvent` (invalidate the `IdenticalIriFinder`
  cache) and `ProjectOpenedEvent` (reconcile + rewrite the graph).

### 8.2 `MatchingService` public methods

```java
MatchingOverviewDto getOverview();
List<MatchingRunSummaryDto> runMatching(MatchingRunRequest request);       // one pair or all pairs
List<MatchLinkDto> listLinks(MatchLinkQuery query);
LinkChangeResultDto changeLinkStatus(String linkId, LinkAction action, String note, boolean dryRun);
LinkChangeResultDto changeLinkStatusBulk(List<String> linkIds, LinkAction action, boolean dryRun);
LinkChangeResultDto createManualLink(CreateManualMatchRequest request, boolean dryRun);
LinkChangeResultDto updateNote(String linkId, String note);
PairComparisonDto comparePair(EntityNodeDto a, EntityNodeDto b);
List<MatchableEntityDto> searchEntities(MatchableEntityQuery query);
List<MatchClusterDto> getClusters();
LinkChangeResultDto removeOrphanedLinks(String dataSourcePairKey /* nullable */, String deletedDataSource /* nullable */);
```

`LinkAction` ∈ `ACCEPT`, `REJECT`, `UNDO`; the transitions are those of §3.2. Any other
transition → 409.

`LinkChangeResultDto { links: [MatchLinkDto] (changed), inferredAdded: [MatchLinkDto],
inferredRemoved: [MatchLinkDto], warnings: [String], applied: boolean }`. With `dryRun=true`,
everything is computed but nothing is saved (`applied=false`). The UI uses this to confirm
actions with side effects ("this undo also removes 2 inferred links").

---

## 9. REST API

`MatchingController` (`/matching`):

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/matching/overview` | — | `MatchingOverviewDto` |
| POST | `/matching/runs` | `MatchingRunRequest { dataSourceA?, dataSourceB?, all? }` | `List<MatchingRunSummaryDto>` |
| GET | `/matching/links` | query: `dataSourceA`, `dataSourceB` (required together), `status` (repeatable), `typeGroupId`, `minScore`, `q`, `includeSuperseded` (false), `includeOrphaned` (true), `offset`, `limit` (200) | `MatchLinkPageDto { total, items }` |
| POST | `/matching/links/{id}/actions` | `LinkActionRequest { action, note? }`, query `dryRun` | `LinkChangeResultDto` |
| POST | `/matching/links/actions` | `BulkLinkActionRequest { ids, action }`, query `dryRun` | `LinkChangeResultDto` |
| PUT | `/matching/links/{id}/note` | `{ note }` | `LinkChangeResultDto` |
| POST | `/matching/links` | `CreateManualMatchRequest { a: EntityNodeDto, b: EntityNodeDto, note? }`, query `dryRun` | `LinkChangeResultDto` |
| DELETE | `/matching/links/orphaned` | query `pair?`, `dataSource?` | `LinkChangeResultDto` |
| GET | `/matching/compare` | query `dataSourceA, iriA, dataSourceB, iriB` | `PairComparisonDto` |
| GET | `/matching/entities` | query `dataSource` (required), `q`, `typeGroupId`, `unmatchedWith` (another source shortName: only entities without a counterpart there), `limit` (50) | `List<MatchableEntityDto>` |
| GET | `/matching/clusters` | query `minSize` (3) | `List<MatchClusterDto>` |

`MatchingConfigurationController` (`/matching/configuration`):

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/matching/configuration` | — | `MatchingConfigurationEnvelopeDto { configuration, origin: "project"\|"default", hash, validation }` |
| GET | `/matching/configuration/default` | — | the bundled configuration (same envelope) |
| PUT | `/matching/configuration` | `MatchingConfigurationDto` (full IRIs) | envelope; 400 with the `ValidationReportDto` if there are errors |
| DELETE | `/matching/configuration` | — | envelope of the default (reset) |
| POST | `/matching/configuration/validate` | `MatchingConfigurationDto` | `ValidationReportDto` |
| GET | `/matching/configuration/vocabulary/types` | — | `List<TypeCandidateDto>` |
| GET | `/matching/configuration/vocabulary/predicates` | query `types` (repeatable) | `List<PredicateCandidateDto>` |
| POST | `/matching/configuration/coverage` | `{ group: TypeGroupDto }` | `GroupCoverageDto` |
| POST | `/matching/configuration/preview` | `{ configuration, dataSourceA, dataSourceB }` | `MatchingPreviewDto` |
| GET | `/matching/configuration/export` | — | the project file as a download (`Content-Disposition`) |
| POST | `/matching/configuration/import` | multipart `file` | envelope (validated, not saved: the GUI loads it into the editor) |

Main DTOs (public fields; the TypeScript interfaces in `models/matching.model.ts` mirror them
one to one):

```
EntityNodeDto        { dataSource, iri, label?, types? }
MatchLinkDto         { id, a: EntityNodeDto, b: EntityNodeDto, status, origin, typeGroupId, typeGroupLabel,
                       score, comparedWeight, lastScore, evidence: ConceptEvidenceDto[], inferredFrom: InferenceStepDto[],
                       proposedAt, decidedAt, note,
                       superseded, orphanA, orphanB, typeMismatch, conflict }          // derived flags
InferenceStepDto     { kind: "link"|"identical", linkId?, from: EntityNodeDto, to: EntityNodeDto, status? }
ConceptEvidenceDto   { concept, comparator, weight, score, valueA, valueB }
MatchingOverviewDto  { dataSources: [{shortName, longName, type, entityCount}],
                       pairs: [{ key, dataSourceA, dataSourceB, counts: {PROPOSED, ACCEPTED, REJECTED, MANUAL, INFERRED, superseded, orphaned},
                                 lastRun: MatchingRunSummaryDto?, stale: boolean, staleReasons: string[] }],
                       conflicts: [ConflictDto], configurationOrigin, configurationHash, fileWarning? }
ConflictDto          { kind: "SAME_SOURCE_IN_CLUSTER"|"REJECTED_INSIDE_CLUSTER", nodes: EntityNodeDto[], linkIds: string[], message }
PairComparisonDto    { a: EntitySideDto, b: EntitySideDto, typeGroupId?, score?, comparedWeight?, proposable, evidence,
                       existingLink?: MatchLinkDto, blockingReasons: string[] }        // why it cannot be linked, if so
EntitySideDto        { node, properties: [{predicate, curie, values: [string]}] }      // statements of its own graph only
MatchableEntityDto   { node, label, typeCuries, matchedWith: EntityNodeDto[] }
MatchClusterDto      { id, nodes: EntityNodeDto[], links: MatchLinkDto[], conflict }
```

Errors: `BadRequestException` (unknown source, same source on both sides, invalid IRI,
configuration errors), `NotFoundException` (link or entity absent),
`ConflictException` (forbidden transition, invariant I1/I2, the message names the blocking
link). All are mapped by `ApiExceptionHandler`.

---

## 10. The matching page (`/data-matching`)

### 10.1 Entry points

- `gestion-sources`, actions bar: two new buttons next to "New Data Source":
  - **Data matching** → `/data-matching`. Disabled, with a tooltip, when the project has fewer
    than two sources.
  - **Matching settings** → `/data-matching/settings`.
- Route `/data-matching?a=<shortName>&b=<shortName>&status=PROPOSED`: the selection is kept in
  query params, so reload and back navigation work.

### 10.2 Layout

```
┌ Data matching ─────────────────────────────────────────────── [⚙ Settings] [Clusters] ┐
│ ⚠ 1 conflict: tropy:item3 and tropy:item9 are both linked to gramps:P001 [Show]          │
├──────────────────────────────────┬─────────────────────────────────────────────────────┤
│ SOURCE PAIRS                     │ gramps ⇄ tropy                       [▶ Run matching]  │
│ ┌──────────────────────────────┐ │ Last run 08/10 10:12 · 5 proposals · ⚠ data changed   │
│ │● gramps ⇄ tropy    5 ▲ 2 ✓ 1 ✗│ │ [To review 3] Accepted 2  Manual 0  Inferred 1         │
│ │  archive ⇄ gramps  never run │ │ Rejected 1  All                                        │
│ │  archive ⇄ tropy   1 ⟳       │ │ Group [All ▾]  Score ≥ [75 %]━━●   🔍 [          ]    │
│ └──────────────────────────────┘ │ ┌──────────────────────────────────────────────────┐   │
│ [▶ Run all pairs]                │ │  gramps            tropy                 score   │   │
│                                  │ │▸ Marie Lefevre      Marie Lefebvre  ████▌ 92 % ✓ ✗│   │
│ Legend: ▲ to review ✓ confirmed  │ │▾ Lyon               Lyon France     █████ 100% ✓ ✗│   │
│ ✗ rejected ⟳ inferred            │ │   name  Lyon ≈ Lyon            100 % ×3          │   │
│                                  │ │   [Compare side by side]  [Open gramps] [Open tropy]│ │
│                                  │ └──────────────────────────────────────────────────┘   │
│                                  │ [+ Manual link]            [Accept all ≥ 95 % (2)…]    │
└──────────────────────────────────┴─────────────────────────────────────────────────────┘
```

### 10.3 Behaviour

- **Pair list** (`matching-pair-list`): every unordered pair (`/matching/overview`), with counts,
  last run and a stale icon whose tooltip lists the reasons. "Run all pairs" runs everything,
  with a spinner and the summaries in a snackbar/dialog.
- **Review panel** (`matching-review-panel`):
  - Header: the run summary, stale reasons, and **Run matching** (with a confirmation if the
    pair already has `PROPOSED` links: they will be recomputed).
  - Tabs by status. *To review* = `PROPOSED` and not superseded; *All* includes superseded
    proposals (badge) and orphans. Group filter, score slider (debounced) and text filter are
    server-side query params. Pagination: 200 per page.
  - Rows show labels, source badges, a score bar, and icons for orphan / type mismatch. Expanding
    a row shows the **evidence** (`matching-evidence`): concept, the two values, the score
    coloured (≥ 0.9 green, ≥ 0.6 amber, else red), and the weight.
  - *Inferred* rows show the chain instead of the evidence, each step clickable. They have no
    action buttons; the tooltip says "undo one of the links of the chain".
  - Actions: ✓ accept, ✗ reject, ↺ undo, 📝 note. Each first calls `?dryRun=true`. If the result
    has side effects (`inferredAdded`/`inferredRemoved` non-empty, or warnings), a small
    confirmation dialog lists them. Otherwise the action is applied directly, and a snackbar
    offers *Undo*.
  - When a proposal is accepted, the competing proposals of the same nodes vanish from *To
    review* (superseded), and the list refreshes.
  - **Keyboard review mode** (when the table has focus): `j`/`k` or ↓/↑ move, `a` accept, `r`
    reject, `u` undo, `space` expand, `c` compare, `m` manual link for the current row's node A.
    The help is shown under `?`.
  - **Bulk**: "Accept all ≥ X %" for the filtered *To review* list, with a confirmation showing
    the count (dry run first).
  - Conflict banner (page level): each conflict with its nodes and links, and an *Open* action
    that filters the list.
- **`matching-comparison-dialog`**: two columns from `/matching/compare`.
  - Compared concepts come first, aligned on one line each (`foaf:name | rico:name`, score in the
    middle).
  - Then the other properties of each entity (its own graph only).
  - Footer: Accept / Reject / Link, depending on the existing link and `blockingReasons`.
- **`manual-match-dialog`**:
  - Two search columns (`/matching/entities`), one per source of the selected pair, with the
    filters *type group* and *only unmatched with the other source* (on by default).
  - Selecting one entity per side calls `/matching/compare` and shows the score, the evidence and
    any `blockingReasons` (I1/I2).
  - **Link** (dry run → confirm if there are side effects → apply).
  - It can be opened pre-filled with one side, from a row ("none of these").
- **Clusters view** (`matching-clusters-dialog`): clusters spanning ≥ 3 nodes, one card each, with
  nodes by source and links by status, and conflicts first.

### 10.4 Frontend files

```
components/data-matching/data-matching.component.*            page, route /data-matching
components/data-matching/matching-pair-list/
components/data-matching/matching-review-panel/
components/data-matching/matching-evidence/                   shared with comparison & manual dialogs
components/data-matching/matching-comparison-dialog/
components/data-matching/manual-match-dialog/
components/data-matching/matching-clusters-dialog/
components/data-matching/link-change-confirm-dialog/
components/matching-settings/ …                               §11.6
services/matching-http.service.ts                             every /matching endpoint (base http://localhost:8080/matching)
models/matching.model.ts                                      interfaces mirroring §9
```

All are standalone components with Material + Tailwind, styled like `gestion-sources`. The
models are typed rather than `any`: the payloads are new and stable.

---

## 11. The settings page (`/data-matching/settings`) — D4

### 11.1 Purpose

The page lets the user, without editing JSON:
- decide which classes are matched together (type groups);
- decide which properties are compared, how, and with what weight;
- set the thresholds;
- see whether the choices fit the actual data (coverage);
- see their effect before saving (preview against the proposals and the decisions already taken).

### 11.2 Layout

```
┌ Matching settings ─────────────── project configuration ● unsaved changes ──────────────┐
│ [Save] [Discard] [Reset to default…] [Import…] [Export]           [Form | JSON]           │
├───────────────────────────┬─────────────────────────────────────────────────────────────┤
│ GLOBAL DEFAULTS            │ GROUP: Person                                    id: person  │
│ Threshold        [0.75]    │ Label [Person            ]  Note [                       ]   │
│ Min. evidence    [2.0 ]    │ Types  (foaf:Person ×) (rico:Person ×) [+ add type…]        │
│ Proposals/entity [5   ]    │        ↳ with subclasses: 2 types · entities: gramps 3, tropy 4│
│ ☐ compare untyped          │ Threshold [0.80] (default 0.75)  Min. evidence [ ] (2.0)     │
│ ☑ include subclasses       │                                                             │
│                            │ PROPERTIES                                     [+ Add concept]│
│ TYPE GROUPS  [+ New group] │ ┌────────┬───────────────────────┬────────┬─────┬───┬───┬──────────┐│
│ ⋮ Person          3 · ✓    │ │Concept │Predicates             │Compare │Wt.  │Req│Blk│Coverage  ││
│ ⋮ Agent           5        │ │name    │foaf:name rico:name +  │name ▾  │3 ━● │ ☑ │ ☑ │g 100 t 75││
│ ⋮ Organization    4        │ │birth…  │foaf:birthday rico:… + │date ▾  │2 ━● │ ☐ │ ☐ │g 100 t 50││
│ ⋮ Document        7  ⚠     │ │num     │rdf:num +              │number ▾│1 ━● │ ☐ │ ☐ │g  33 t 25││
│ ⋮ Place           2        │ └────────┴───────────────────────┴────────┴─────┴───┴───┴──────────┘│
│   (drag to reorder)        │ ▸ Suggested predicates used by these types (4)                │
│                            │ ⚠ 1 warning · ✕ 0 error                                       │
├───────────────────────────┴─────────────────────────────────────────────────────────────┤
│ PREVIEW  Pair [gramps ⇄ tropy ▾]  [▶ Preview]                                             │
│ Proposals 5 → 6 (+2 −1)  ·  Decisions: accepted 2/2 above threshold · rejected 0/1 above  │
│ Score histogram ▁▂▁▃▅█▆  ·  [Show new / lost proposals]                                   │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 11.3 Editing behaviour

- The page loads `GET /matching/configuration` into a local, mutable copy (a deep clone). Edits
  are local until **Save** (`PUT`). The "unsaved changes" state guards navigation
  (`CanDeactivate`).
- **Validation is live**: `POST /validate` debounced (500 ms) after each edit. Issues are shown
  next to the field matched by `path`, and summarised under the group. Save is disabled while
  there are errors.
- **Global defaults**: number fields with the ranges of §5.3. Each has a help tooltip in plain
  words, e.g. *"Min. evidence: the total weight of the properties that must be comparable before
  a pair can be proposed. Prevents a match on a single minor property."*
- **Group list**: drag to reorder (the order breaks ties, §4.4), duplicate, delete (with a
  confirmation if links reference the group). A ⚠/✕ marker shows groups with warnings or errors.
  New group: label → `id` slug generated, editable until the first save, then read-only.
- **Types** (chips + autocomplete over `/vocabulary/types`):
  - Each option shows CURIE, label, ontology, and *defined* vs *used only*.
  - Each option shows entity counts per source.
  - Below the chips, a line summarises the expansion with subclasses and the number of entities
    per source in the group.
- **Concepts table**:
  - *Concept*: key, editable inline (pattern-checked).
  - *Predicates*: chips + autocomplete over `/vocabulary/predicates?types=…`. Options show the
    CURIE, the kind (literal datatype / object property), whether the predicate is defined in an
    ontology, and usage counts per source. A predicate used in the data but unknown to the
    ontologies is offered too: that is how used-only ontologies get matched.
  - *Compare*: comparator drop-down with a one-line explanation per comparator. When the chosen
    comparator does not fit the predicates' kinds (e.g. `date` on object properties not pointing
    to `rico:Date`), an inline warning is shown.
  - *Weight*: slider 0.5–10 (step 0.5) + numeric input (up to 100).
  - *Req*: required concept. *Blk*: blocking key.
  - *Options*: a popover for comparator options (`partialOverlapScore`, `tolerance`,
    `labelFactor`, `caseSensitive`).
  - *Coverage*: per source, the % of the group's entities having ≥ 1 value for the concept
    (`POST /coverage`, refreshed when types or predicates change). Low coverage on a required
    concept is shown in red, because it means "these entities will never be proposed".
- **Suggested predicates**: a collapsible list of the predicates actually used by the group's
  entities and not yet in any concept, with counts and sample values. A suggested comparator is
  attached:
  - date datatypes → `date`;
  - numeric → `number`;
  - IRI → `linkedEntity`;
  - LABEL_PREDICATES → `name`;
  - strings with an average length > 100 → `text`;
  - else `string`.

  "Add as new concept" or "add to concept…".
- **JSON tab**: the full configuration in a monospace text area (CURIE form), validated on blur.
  "Apply to form" replaces the local copy. This serves power users and pasted configurations.
- **Import/Export**: export downloads the effective configuration. Import loads a file into the
  editor (not saved until Save).
- **Reset to default**: confirm, then `DELETE`. The project file is removed and the page reloads
  the default.
- After a save that changes the hash, a snackbar says *"Proposals of N pairs are now out of date
  — open Data matching to rerun"* with a link.

### 11.4 Vocabulary endpoints (`MatchingVocabularyService`)

- `types`: the union of
  - (a) the types of every DefinedOntology (from `OntologyService`'s schema cache, with labels);
  - (b) the types used in the data graphs (`SELECT ?g ?t (COUNT(DISTINCT ?s)) … GROUP BY ?g ?t`).

  `TypeCandidateDto { iri, curie, label?, ontologyNamespace?, defined, usage: {shortName: count} }`.
- `predicates?types=…`: for the entities whose type ∈ expanded types, per data graph, every
  predicate with:
  - `count` (distinct subjects);
  - `kind` (`literal:<datatype>` / `iri`, majority);
  - average literal length;
  - up to 3 sample values.

  This is merged with the properties of the DefinedOntologies whose domain is one of the
  (super)types (`OntologyService` schema; `defined: true`, `count: 0` when unused). Reuse
  `UsageProfileService.describeType` if its output fits; otherwise a dedicated query in the same
  style.
  `PredicateCandidateDto { iri, curie, label?, defined, kind, usage: {shortName: count}, sampleValues, averageLength?, suggestedComparator }`.
- `coverage`: for the group's expanded types, per source: `entityCount`, and per concept
  `entitiesWithValue` and the percentage. One query per source with `OPTIONAL`/`COUNT` per
  concept, or one query per concept: the volumes are small.

### 11.5 Preview (`MatchingPreviewService`)

`POST /preview { configuration, dataSourceA, dataSourceB }` runs §4 steps 3–8 with the
**unsaved** configuration, and writes nothing. It returns `MatchingPreviewDto`:
- `summary` (as §4.2);
- `proposalDiff { added: n, removed: n, unchanged: n, samplesAdded: [≤ 20], samplesRemoved: [≤ 20] }`,
  computed against the pair's current `PROPOSED` links;
- `decisionAgreement`: every `ACCEPTED`/`MANUAL`/`REJECTED` link of the pair rescored with the
  new configuration, ignoring the exclusions:
  `{ confirmedAbove, confirmedBelow, rejectedAbove, rejectedBelow, samples }`. The user's past
  decisions serve as ground truth: "with these settings, 1 pair you rejected would be proposed
  again (if it were not rejected)" and "2 pairs you confirmed would not have been found".
- `scoreHistogram`: 20 bins over [0,1] of all scored candidate pairs (not only those above the
  threshold), with the threshold drawn on it.

The preview is limited to one pair. With blocking, it takes seconds; a spinner and a *Cancel*
button (which ignores the result) are enough in v1.

### 11.6 Settings components

```
components/matching-settings/matching-settings.component.*      page, route /data-matching/settings
components/matching-settings/matching-defaults-form/
components/matching-settings/matching-group-list/               cdkDropList
components/matching-settings/matching-group-editor/
components/matching-settings/matching-concept-table/
components/matching-settings/iri-chip-input/                     shared autocomplete chips (types and predicates)
components/matching-settings/matching-preview-panel/
components/matching-settings/matching-json-editor/
services/matching-settings-http.service.ts                       /matching/configuration endpoints
```

---

## 12. Tests and fixtures

Add JUnit 5 tests (the starter is already there). Pure classes are tested without Spring. The
service-level tests use an in-memory `SailRepository(new MemoryStore())` installed with
`ProjectContext.set(repo, "fixture")` and a temporary `ProjectsDirectory`. They avoid
`@SpringBootTest`, which needs `FIELD_ARCHIVE_DATA`.

### 12.1 Fixture (`src/test/resources/matching/`)

- `fixture.trig`, converted once from the CSVs (D7):
  - graph `urn:datasource:tropy` = `external_datasource.csv`;
  - graph `urn:datasource:gramps` = `internal_datasource.csv`;
  - graph `urn:datasource:archive` = 2 hand-written entities (`archive:A1`, a `foaf:Person`
    named "Marie Lefèvre" born 1948; `archive:A2`, a `rico:Place` named "Lyon").
  - Conversion: `typevalue` `string` → plain literal; `date` → `xsd:gYear`/`xsd:date` by pattern;
    `number` → `xsd:integer`; `iri` → IRI; `rdf:` CURIEs expanded.
  - The `matchedWith`/`matchStatus` rows are **not** converted; they become
    `fixture-decisions.json`.
  - The metadata graph declares the three sources (`app:shortName`, `app:graph`).
- `fixture-decisions.json`: `tropy:item3 ≡ gramps:P001` `ACCEPTED`;
  `tropy:item7 ≢ gramps:PL001` `REJECTED`.
- `fixture-configuration.json` has 4 groups:
  - Person [`foaf:Person`, `rico:Person`]: `name` (name, 3, required, blocking) over
    `foaf:name`, `rico:name`; `birthDate` (date, 2) over `foaf:birthday`, `rico:date`; `num`
    (number, 1) over `rdf:num`.
  - Place [`rico:Place`]: `name` (name, 3, required).
  - Organization [`foaf:Organization`, `rico:CorporateBody`]: `name` (name, 3, required);
    `date` (date, 1) over `rico:date`, `rico:foundingDate`.
  - Record [`rico:Record`]: `title` (string, 3, required); `date` (date, 1); `identifier`
    (exact, 4).
  - Defaults: threshold 0.75, minimumComparedWeight 2.

### 12.2 Expected results, pair `gramps ⇄ tropy`

Scores were computed with the §4.7 formulas:

| Pair | Notebook | Expected | Why |
|---|---|---|---|
| item1 / item1 | removed | identical IRI, never proposed, counted | A15 |
| item3 / P001 | removed (confirmed) | excluded (already matched) | decision |
| item7 / PL001 | rejected | excluded (rejected) | decision |
| item2 / P002 | **missed** (no common column) | **PROPOSED 0.918** = (3·0.963 + 2·0.85)/5 | A1: `rico:name`↔`foaf:name`, `rico:date`↔`foaf:birthday` |
| item5 / CB001 | **missed** | **PROPOSED 1.0** (name "Musee d'Orsay" = "Musée d'Orsay" after folding) | A1 + A12 |
| item4 / PL002 | proposed | **PROPOSED 1.0** (best of "Lyon", "Lyon France" vs "Lyon") | multi-value |
| item4 / PL001 | — | not proposed (Lyon / Paris = 0.0; Lyon France / Paris = 0.125) | |
| item6 / P002 | — | not proposed (name 0.167, date 0) | |
| item8 / P002 | **proposed 85** | **not proposed**: no name (required) | A7 |
| item9 / P003 | **proposed 100** | **not proposed**: no name (required) | A7 |
| item7 / R001 | — | not proposed: (3·0.349 + 1·0.85)/4 = 0.474 | |

Then:
- the run summary counts are asserted;
- rerunning yields identical proposals (determinism);
- `score(a,b) == score(b,a)` for every scored pair.

### 12.3 Scenario tests (`MatchingServiceScenarioTest`)

1. **Accept**: accepting item2/P002 → `ACCEPTED`; a rerun on gramps⇄tropy does not propose
   item2 or P002 and keeps the link.
2. **Inferred (D6)**: run archive⇄gramps → P002/A1 proposed. Accepting it creates the
   `INFERRED` link item2/A1 in archive⇄tropy, with `inferredFrom` = [the two link ids].
   - Run archive⇄tropy → no proposal for item2 or A1.
   - The `owl:sameAs` graph holds 8 triples: 6 among {item2, P002, A1} plus 2 for the fixture's item3/P001.
3. **Undo cascade**: undoing P002/A1 (dry run first) → `inferredRemoved` = [item2/A1]. Once
   applied:
   - the inferred link is gone;
   - archive⇄tropy is stale with reason `decisionsRevoked`;
   - the `owl:sameAs` graph has 4 triples (item2/P002 and item3/P001).
4. **I1**: with item2/P002 accepted, a manual link item6/P002 → 409, naming the link item2/P002.
5. **I2**: reject item4/PL002, then try to accept a path that joins them via archive
   (A2/PL002 accepted, then item4/A2) → 409 naming the rejected link.
6. **Proposal → inferred**: a `PROPOSED` link that becomes inferable turns into `INFERRED` and
   keeps its evidence.
7. **Orphans**: deleting P002's statements → links flagged `orphanA`; "remove orphaned" deletes
   them and reconciles.
8. **Backup round trip**: export, then import into a new project → identical
   `data_matching.json`, the same configuration file, and the same `owl:sameAs` graph.
9. **Configuration**: the shorthand notebook JSON loads; CURIEs round-trip; validation reports
   the expected `path`s; a hash change makes every pair stale (`configurationChanged`).

### 12.4 Unit tests

`StringSimilarityTest`:

| a | b | indel ratio | name |
|---|---|---|---|
| Marie Lefebvre | Marie Lefevre | 0.963 | |
| Dupont, Jean | Jean Dupont | 0.545 (punctuation removed) | 1.0 |
| Musee d'Orsay | Musée d'Orsay | 1.0 | |
| Lyon France | Paris | 0.125 | |

Also: `PartialDateTest` (formats of §4.7, timezones, intervals, garbage → empty),
`DateComparatorTest`, `TypeGroupResolverTest` (the 4 rows of the §4.4 table, A3/A4 regressions),
`MatchClustersTest` (union-find, paths, conflicts), `ProposalSelectionTest`,
`MatchingConfigurationValidatorTest`.

Frontend: no tests are required by the project. Add one spec for `matching-http.service`, as an
example.

---

## 13. Implementation phases

| # | Content | Done when |
|---|---|---|
| 1 | `config/` (model, loader, writer, validator, default file), `scoring/`, `profile/`, `candidate/` without blocking, `cluster/`, `StringSimilarity`, unit tests + fixture §12.1–12.2 | §12.2 and §12.4 green |
| 2 | `ProjectJsonFile` (+ `NlQueryHistoryService` migrated), `MatchStore`, `MatchReconciler`, `MatchingService`, `SameAsGraphWriter`, `ProjectOpenedEvent`, `MatchingController`, `getByIri.sameAs`, backup/restore, `NlPromptBuilder` line | §12.3 green; a `curl` walkthrough on a real project |
| 3 | Matching page (§10) + "Same as" row in `entity-details` + buttons on `gestion-sources` | Manual walkthrough of §12.3 scenarios 1–4 in the UI |
| 4 | Configuration service/controller (project file, PUT/DELETE/validate/import/export), vocabulary + coverage endpoints, settings page (§11) without preview | Editing, saving, resetting and JSON round trips work; validation messages land on the right fields |
| 5 | Preview + decision agreement; blocking; clusters dialog; bulk accept | Preview on the fixture shows the expected diff; a 5 000 × 5 000 synthetic run in < 10 s |
| 6 | Docs: `CLAUDE.md` (matching section, endpoints, graphs table + `CTX_MATCHING`, JSON files), `Doc/Manual.md` (user guide of both pages), `README` endpoint list | — |

---

## 14. Later

- **Async runs** with progress and cancel (`POST /matching/runs` → runId; `GET` status; cancel),
  through the existing `MatchingProgressListener`.
- **Learning weights from decisions**: confirmed and rejected links are labelled data. A
  Fellegi–Sunter EM estimate of per-concept m/u probabilities could suggest weights in the
  settings page ("suggested weight: 2.4") behind the same `PairScorer` interface.
- **A merged entity view**: `entity-details` showing the values of all `owl:sameAs` members,
  with source badges (the backend already tags sources).
- **Expressed dates** ("ca. 1950", "début des années 60") in `PartialDate`.
- **Matching the internal source against a new external source at import time**, offered right
  after the data source is created.
