package fr.cnrs.lacito.fieldarchive.services.nlquery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.NlClarification;
import fr.cnrs.lacito.fieldarchive.dtos.NlHistoryContext;
import fr.cnrs.lacito.fieldarchive.dtos.NlHistoryEntry;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.utils.ProjectsDirectory;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-project history of natural-language questions and the SPARQL actually run for them,
 * in {@code <project dir>/natural_language_query_history.json}. Past entries are given to the
 * agents as worked examples, so they reuse the RDF encodings the project has settled on.
 */
@Service
public class NlQueryHistoryService {

    public static final String FILE_NAME = "natural_language_query_history.json";
    public static final String PLACEHOLDER_PREFIX = "urn:fieldarchive:new:";

    private static final Pattern IRI_REF = Pattern.compile("<([^<>\\s]+)>");
    private static final List<String> VOCABULARY_NAMESPACES = List.of(
            RdfNamespaces.RICO, "http://www.w3.org/", "http://purl.org/", "http://xmlns.com/",
            "https://w3id.org/", "http://id.loc.gov/", "urn:datasource:", PLACEHOLDER_PREFIX, RdfNamespaces.APP);

    private final ProjectsDirectory projectsDirectory;
    private final RdfEntityService rdfEntityService;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public NlQueryHistoryService(ProjectsDirectory projectsDirectory, RdfEntityService rdfEntityService) {
        this.projectsDirectory = projectsDirectory;
        this.rdfEntityService = rdfEntityService;
    }

    /** The on-disk shape of the file. */
    public static class HistoryFile {
        public int version = 1;
        public List<NlHistoryEntry> entries = new ArrayList<>();
    }

    // =========================
    //  File access
    // =========================

    public Path fileOf(String projectName) {
        return projectsDirectory.getPublicPath().resolve(projectName).resolve(FILE_NAME);
    }

    private Path currentFile() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("Aucun projet ouvert.");
        return fileOf(ProjectContext.getProjectName());
    }

    private synchronized HistoryFile read(Path file) {
        if (!Files.exists(file)) return new HistoryFile();
        try {
            HistoryFile h = mapper.readValue(file.toFile(), HistoryFile.class);
            if (h.entries == null) h.entries = new ArrayList<>();
            return h;
        } catch (IOException e) {
            // A broken file must never block the dialog: set it aside and start a new history.
            Path aside = file.resolveSibling(FILE_NAME + ".corrupt-" + Instant.now().toEpochMilli());
            try {
                Files.move(file, aside);
            } catch (IOException ignored) {
                // nothing more we can do; the next write replaces the file
            }
            System.err.println("WARNING: unreadable " + file + " moved to " + aside + ": " + e.getMessage());
            return new HistoryFile();
        }
    }

    private synchronized void write(Path file, HistoryFile h) {
        try {
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            mapper.writeValue(tmp.toFile(), h);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Raw bytes of a project's history file, or null when there is none (for backups). */
    public byte[] rawContent(String projectName) {
        Path f = fileOf(projectName);
        try {
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Restores a project's history file from a backup. */
    public void restore(String projectName, byte[] content) throws IOException {
        Path f = fileOf(projectName);
        Files.createDirectories(f.getParent());
        Files.write(f, content);
    }

    // =========================
    //  Entries
    // =========================

    public List<NlHistoryEntry> list() {
        List<NlHistoryEntry> entries = new ArrayList<>(read(currentFile()).entries);
        entries.sort(Comparator.comparing((NlHistoryEntry e) -> e.timestamp == null ? "" : e.timestamp).reversed());
        return entries;
    }

    public synchronized void delete(String id) {
        Path f = currentFile();
        HistoryFile h = read(f);
        if (!h.entries.removeIf(e -> Objects.equals(e.id, id))) {
            throw new NotFoundException("History entry not found: " + id);
        }
        write(f, h);
    }

    public void record(NlHistoryContext nl, String queryRun, String queryType, Integer resultCount) {
        record(nl, queryRun, queryType, resultCount, null);
    }

    /**
     * Called after a query coming from a question ran successfully. Never throws.
     *
     * @param entitiesBefore entity labels read before an update ran (null: read them now)
     */
    public synchronized void record(NlHistoryContext nl, String queryRun, String queryType, Integer resultCount,
                                    List<NlHistoryEntry.EntityRef> entitiesBefore) {
        try {
            if (nl == null || nl.question == null || nl.question.isBlank()) return;
            Path f = currentFile();
            HistoryFile h = read(f);

            String normalizedQuestion = String.join(" ", TextNormalizer.tokens(nl.question));
            String normalizedRun = normalizeSparql(queryRun);
            for (NlHistoryEntry e : h.entries) {
                if (normalizedQuestion.equals(String.join(" ", TextNormalizer.tokens(e.question)))
                        && normalizedRun.equals(normalizeSparql(e.sparql))) {
                    e.useCount++;
                    e.timestamp = Instant.now().toString();
                    e.resultCount = resultCount;
                    write(f, h);
                    return;
                }
            }

            NlHistoryEntry e = new NlHistoryEntry();
            e.id = UUID.randomUUID().toString();
            e.timestamp = Instant.now().toString();
            e.question = nl.question.trim();
            e.clarifications = nl.clarifications != null ? nl.clarifications : new ArrayList<>();
            e.queryType = queryType;
            e.sparql = queryRun;
            boolean edited = nl.generatedSparql != null && !normalizeSparql(nl.generatedSparql).equals(normalizedRun);
            e.edited = edited;
            e.generatedSparql = edited ? nl.generatedSparql : null;
            e.targetEntityIri = nl.targetEntityIri;
            e.createdEntityIris = nl.createdEntityIris != null ? nl.createdEntityIris : new ArrayList<>();
            e.resultCount = resultCount;
            e.provider = nl.provider;
            e.model = nl.model;
            e.entities = new ArrayList<>(entitiesBefore != null ? entitiesBefore : entitiesIn(queryRun));
            // entities created by this query only have a name now
            for (NlHistoryEntry.EntityRef ref : entitiesIn(queryRun)) {
                if (e.entities.stream().noneMatch(x -> x.iri.equals(ref.iri))) e.entities.add(ref);
            }
            h.entries.add(e);
            write(f, h);
        } catch (RuntimeException ex) {
            System.err.println("WARNING: could not record the natural-language history: " + ex.getMessage());
        }
    }

    /** Entity IRIs written in a query (not vocabulary terms), with their current label. */
    public List<NlHistoryEntry.EntityRef> entitiesIn(String sparql) {
        List<NlHistoryEntry.EntityRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = IRI_REF.matcher(sparql == null ? "" : sparql);
        while (m.find()) {
            String iri = m.group(1);
            if (!seen.add(iri) || isVocabulary(iri) || !iri.contains(":")) continue;
            try {
                String label = rdfEntityService.bestLabel(SimpleValueFactory.getInstance().createIRI(iri));
                if (label != null) out.add(new NlHistoryEntry.EntityRef(iri, label));
            } catch (IllegalArgumentException ignored) {
                // not an IRI
            }
        }
        return out;
    }

    private static boolean isVocabulary(String iri) {
        for (String ns : VOCABULARY_NAMESPACES) if (iri.startsWith(ns)) return true;
        return false;
    }

    public static String normalizeSparql(String sparql) {
        return sparql == null ? "" : sparql.replaceAll("\\s+", " ").trim();
    }

    // =========================
    //  Examples for the agents
    // =========================

    private static boolean isDecision(NlHistoryEntry e) {
        return e.clarifications != null && e.clarifications.stream().anyMatch(c -> "ENCODING".equals(c.kind));
    }

    /** The {@code n} entries most relevant to a question; with fewer than 3 hits, filled up with the most recent. */
    public List<NlHistoryEntry> selectExamples(String question, int n) {
        if (n <= 0 || !ProjectContext.isOpen()) return List.of();
        List<NlHistoryEntry> all = read(currentFile()).entries;
        List<NlHistoryEntry> ranked = rank(question, all);
        List<NlHistoryEntry> out = new ArrayList<>(ranked.subList(0, Math.min(n, ranked.size())));
        if (out.size() < 3) {
            List<NlHistoryEntry> recent = new ArrayList<>(all);
            recent.sort(Comparator.comparing((NlHistoryEntry e) -> e.timestamp == null ? "" : e.timestamp).reversed());
            for (NlHistoryEntry e : recent) {
                if (out.size() >= Math.min(n, 3)) break;
                if (!out.contains(e)) out.add(e);
            }
        }
        return out;
    }

    public List<NlHistoryEntry> search(String text, int n) {
        if (!ProjectContext.isOpen()) return List.of();
        List<NlHistoryEntry> ranked = rank(text, read(currentFile()).entries);
        return ranked.subList(0, Math.min(n, ranked.size()));
    }

    private List<NlHistoryEntry> rank(String text, List<NlHistoryEntry> entries) {
        Set<String> q = TextNormalizer.contentWords(text);
        Map<NlHistoryEntry, Double> score = new HashMap<>();
        String newest = entries.stream().map(e -> e.timestamp == null ? "" : e.timestamp).max(String::compareTo).orElse("");
        for (NlHistoryEntry e : entries) {
            double s = TextNormalizer.jaccard(q, TextNormalizer.contentWords(e.question));
            if (s <= 0) continue;
            if (e.edited || isDecision(e)) s += 0.15;
            s += Math.min(0.05, 0.01 * e.useCount);
            if (newest.equals(e.timestamp)) s += 0.01;
            score.put(e, s);
        }
        List<NlHistoryEntry> ranked = new ArrayList<>(score.keySet());
        ranked.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return ranked;
    }

    /**
     * Renders entries for the prompt. Entity labels are re-read now, so the model sees current
     * names; IRIs minted by a past "create" become placeholders, so they are never re-inserted.
     */
    public String render(List<NlHistoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (NlHistoryEntry e : entries) {
            sb.append("Q: ").append(e.question);
            if (e.edited) sb.append("   [corrected by the user]");
            if (e.clarifications != null) {
                for (NlClarification c : e.clarifications) {
                    if ("ENCODING".equals(c.kind)) sb.append("   [decided by the user: ").append(c.statement).append(']');
                }
            }
            sb.append("   [").append(e.queryType);
            if (e.resultCount != null) sb.append(", ").append(e.resultCount).append(" results");
            sb.append("]\n");

            String sparql = withPlaceholders(e.sparql, e.createdEntityIris);
            List<String> ents = new ArrayList<>();
            for (NlHistoryEntry.EntityRef ref : e.entities) {
                if (e.createdEntityIris != null && e.createdEntityIris.contains(ref.iri)) continue;
                String label = null;
                try {
                    label = rdfEntityService.bestLabel(SimpleValueFactory.getInstance().createIRI(ref.iri));
                } catch (RuntimeException ignored) {
                    // leave null
                }
                ents.add("<" + ref.iri + "> = " + (label == null ? "(no longer exists)" : "\"" + label + "\""));
            }
            if (!ents.isEmpty()) sb.append("Entities: ").append(String.join("; ", ents)).append('\n');
            sb.append("SPARQL run: ").append(normalizeSparql(sparql)).append('\n');
            if (e.edited && e.generatedSparql != null) {
                sb.append("(Generated before correction: ")
                        .append(normalizeSparql(withPlaceholders(e.generatedSparql, e.createdEntityIris))).append(")\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String withPlaceholders(String sparql, List<String> createdIris) {
        if (sparql == null || createdIris == null) return sparql;
        String out = sparql;
        int i = 1;
        for (String iri : createdIris) {
            out = out.replace("<" + iri + ">", "<" + PLACEHOLDER_PREFIX + i++ + ">");
        }
        return out;
    }
}
