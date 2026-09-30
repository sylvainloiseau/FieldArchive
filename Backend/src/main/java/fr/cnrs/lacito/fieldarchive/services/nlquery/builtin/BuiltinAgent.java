package fr.cnrs.lacito.fieldarchive.services.nlquery.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.EntityMatchDto;
import fr.cnrs.lacito.fieldarchive.dtos.NlClarification;
import fr.cnrs.lacito.fieldarchive.dtos.NlHistoryEntry;
import fr.cnrs.lacito.fieldarchive.dtos.ProviderStatusDto;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.NlQueryHistoryService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.Prefixes;
import fr.cnrs.lacito.fieldarchive.services.nlquery.RicoDigestService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.UsageProfileService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.llm.DirectAgent;
import fr.cnrs.lacito.fieldarchive.services.nlquery.llm.ModelOutput;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in agent: a deterministic translator for a fixed set of question shapes, in French
 * and English. No AI, nothing to install, always available. It uses the same entity search,
 * validation and history as the LLM agents, and reuses past questions of the same shape.
 * Its vocabulary comes from the RiC-O digest, the project's usage profile and a small lexicon
 * ({@code nlquery/builtin-lexicon.json}); no other ontology file is read.
 */
@Component
public class BuiltinAgent implements DirectAgent {

    private static final String NAME = RdfNamespaces.RICO + "name";
    private static final String PLACEHOLDER = NlQueryHistoryService.PLACEHOLDER_PREFIX;

    static final List<String> EXAMPLES = List.of(
            "list all persons", "liste des personnes", "how many records", "combien de lieux",
            "persons named Dupont", "records whose title contains chant", "show Marie Dupont",
            "create a person named Marie Dupont", "rename Marie Dupont to Marie Durand",
            "set the description of Marie Dupont to …", "add the gender 'masculine' to Jean Dupond",
            "delete Marie Dupont");

    private final RdfEntityService rdfEntityService;
    private final RicoDigestService ricoDigest;
    private final UsageProfileService usageProfile;
    private final NlQueryHistoryService history;
    private final DataSourceService dsService;
    private final Prefixes prefixes;

    private final Map<String, String> lexiconTypes = new HashMap<>();
    private final Map<String, String> lexiconProperties = new HashMap<>();

    public BuiltinAgent(RdfEntityService rdfEntityService, RicoDigestService ricoDigest, UsageProfileService usageProfile,
                        NlQueryHistoryService history, DataSourceService dsService, Prefixes prefixes) {
        this.rdfEntityService = rdfEntityService;
        this.ricoDigest = ricoDigest;
        this.usageProfile = usageProfile;
        this.history = history;
        this.dsService = dsService;
        this.prefixes = prefixes;
        try (InputStream is = new ClassPathResource("nlquery/builtin-lexicon.json").getInputStream()) {
            JsonNode lex = new ObjectMapper().readTree(is);
            lex.path("types").fields().forEachRemaining(e -> lexiconTypes.put(e.getKey(), prefixes.expand(e.getValue().asText())));
            lex.path("properties").fields().forEachRemaining(e -> lexiconProperties.put(e.getKey(), prefixes.expand(e.getValue().asText())));
        } catch (Exception e) {
            System.err.println("WARNING: cannot read nlquery/builtin-lexicon.json: " + e.getMessage());
        }
    }

    @Override public String id() { return "builtin"; }
    @Override public String label() { return "Built-in (no AI)"; }
    @Override public String model() { return "patterns"; }

    @Override
    public ProviderStatusDto status() {
        ProviderStatusDto s = new ProviderStatusDto();
        s.id = id();
        s.label = label();
        s.model = model();
        s.available = true;
        s.free = true;
        return s;
    }

    // =========================
    //  Per-request context
    // =========================

    private final class Ctx {
        final String question;
        final List<NlClarification> clarifications;
        final UsageProfileService.Profile profile = usageProfile.profile();
        final IRI internal = dsService.getGraphIri(ProjectContext.getProjectName() + "_internal");
        final List<String> lookups = new ArrayList<>();
        final Set<String> usedNamespaces = new LinkedHashSet<>();

        Ctx(String question, List<NlClarification> clarifications) {
            this.question = question;
            this.clarifications = clarifications;
        }

        String n(String iri) {
            String c = Prefixes.curie(iri, profile.prefixes);
            if (!c.startsWith("<")) usedNamespaces.add(c.substring(0, c.indexOf(':')));
            return c;
        }

        String header() {
            StringBuilder sb = new StringBuilder();
            for (String p : usedNamespaces) sb.append("PREFIX ").append(p).append(": <").append(profile.prefixes.get(p)).append(">\n");
            return sb.toString();
        }

        long typeCount(String type) {
            for (UsageProfileService.TypeUsage t : profile.types) if (t.type.equals(type)) return t.count;
            return 0;
        }

        UsageProfileService.PredicateUsage usage(String type, String predicate) {
            for (UsageProfileService.TypeUsage t : profile.types) {
                if (!t.type.equals(type)) continue;
                for (UsageProfileService.PredicateUsage pu : t.predicates) if (pu.predicate.equals(predicate)) return pu;
            }
            return null;
        }
    }

    /** Thrown to stop at a CLARIFY or an explanation. */
    private static final class Stop extends RuntimeException {
        final ModelOutput output;
        Stop(ModelOutput output) { super(null, null, false, false); this.output = output; }
    }

    private static ModelOutput out(String queryType, String sparql, String explanation) {
        ModelOutput o = new ModelOutput();
        o.queryType = queryType;
        o.sparql = sparql == null ? "" : sparql;
        o.explanation = explanation;
        return o;
    }

    // =========================
    //  Entry point
    // =========================

    @Override
    public DirectAnswer translate(String question, List<NlClarification> clarifications) {
        String q = question.trim().replaceAll("[\\s?!.]+$", "").replaceAll("\\s+", " ");
        Ctx ctx = new Ctx(q, clarifications == null ? List.of() : clarifications);
        try {
            ModelOutput reused = fromHistory(ctx);
            if (reused != null) return new DirectAnswer(reused, ctx.lookups, List.of());
            for (Shape shape : shapes()) {
                ModelOutput o = shape.apply(ctx);
                if (o != null) return new DirectAnswer(o, ctx.lookups, List.of());
            }
        } catch (Stop s) {
            return new DirectAnswer(s.output, ctx.lookups, List.of());
        }
        ModelOutput o = out("UNSUPPORTED", null,
                "The built-in agent does not understand this phrasing. It knows a fixed set of question shapes, such as the examples below. "
                        + "An AI agent (Claude, ChatGPT or a local model) can handle free-form questions.");
        return new DirectAnswer(o, ctx.lookups, EXAMPLES);
    }

    private interface Shape { ModelOutput apply(Ctx ctx); }

    private List<Shape> shapes() {
        return List.of(this::create, this::rename, this::addValue, this::setValue, this::delete,
                this::count, this::filterNamed, this::filterContains, this::list, this::show);
    }

    private static Matcher match(String regex, String folded) {
        Matcher m = Pattern.compile(regex).matcher(folded);
        return m.matches() ? m : null;
    }

    private static String group(TextNormalizer.Mapped mapped, Matcher m, String name) {
        String s = mapped.original(m.start(name), m.end(name)).trim();
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("«") && s.endsWith("»")) || (s.startsWith("“") && s.endsWith("”")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String esc(String literal) {
        return literal.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final String ARTICLE = "(?:the |a |an |le |la |les |l'|un |une |des |du |de la |de l'|tous les |toutes les |all |every )?";

    // =========================
    //  Vocabulary
    // =========================

    private static String singular(String w) {
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if ((w.endsWith("s") || w.endsWith("x")) && w.length() > 3) return w.substring(0, w.length() - 1);
        return w;
    }

    private static String key(String phrase) {
        List<String> words = new ArrayList<>();
        for (String w : TextNormalizer.tokens(phrase)) words.add(singular(w));
        return String.join(" ", words);
    }

    /** A class for a phrase: lexicon first, then types present in the data, then RiC-O labels. */
    private String typeFor(Ctx ctx, String phrase) {
        String k = key(phrase.replaceFirst("^" + ARTICLE, ""));
        if (k.isEmpty()) return null;
        for (Map.Entry<String, String> e : lexiconTypes.entrySet()) if (key(e.getKey()).equals(k)) return e.getValue();
        String best = null;
        long bestCount = -1;
        for (UsageProfileService.TypeUsage t : ctx.profile.types) {
            if (key(TextNormalizer.splitLocalName(Prefixes.localName(t.type))).equals(k) && t.count > bestCount) {
                best = t.type;
                bestCount = t.count;
            }
        }
        if (best != null) return best;
        for (RicoDigestService.Entry e : ricoDigest.entries()) {
            if (e.kind != RicoDigestService.Kind.CLASS) continue;
            if (key(TextNormalizer.splitLocalName(Prefixes.localName(e.iri))).equals(k)) return e.iri;
            for (String l : e.allLabels) if (key(l).equals(k)) return e.iri;
        }
        return null;
    }

    /** A property for a phrase, on entities of the given types: lexicon, data usage, RiC-O labels. */
    private String propertyFor(Ctx ctx, String phrase, List<String> types) {
        String k = key(phrase.replaceFirst("^" + ARTICLE, ""));
        if (k.isEmpty()) return null;
        for (Map.Entry<String, String> e : lexiconProperties.entrySet()) if (key(e.getKey()).equals(k)) return e.getValue();
        for (String type : types) {
            for (UsageProfileService.TypeUsage t : ctx.profile.types) {
                if (!t.type.equals(type)) continue;
                for (UsageProfileService.PredicateUsage pu : t.predicates) {
                    if (key(TextNormalizer.splitLocalName(Prefixes.localName(pu.predicate))).equals(k)) return pu.predicate;
                }
            }
        }
        for (RicoDigestService.Entry e : ricoDigest.entries()) {
            if (e.kind == RicoDigestService.Kind.CLASS) continue;
            if (key(TextNormalizer.splitLocalName(Prefixes.localName(e.iri))).equals(k)) return e.iri;
            for (String l : e.allLabels) if (key(l).equals(k)) return e.iri;
        }
        return null;
    }

    // =========================
    //  Entity resolution
    // =========================

    /** One existing entity for a name, or a Stop with a CLARIFY (none or several candidates). */
    private EntityMatchDto resolve(Ctx ctx, String name, String typeIri) {
        for (NlClarification c : ctx.clarifications) {
            if (!"ENTITY".equals(c.kind) || c.entityIri == null) continue;
            EntityMatchDto m = rdfEntityService.matchOf(SimpleValueFactory.getInstance().createIRI(c.entityIri));
            if (m != null && m.label != null && sameName(m.label, name)) {
                ctx.lookups.add("decided by the user: \"" + name + "\" is <" + m.iri + ">");
                return m;
            }
        }
        List<EntityMatchDto> matches = rdfEntityService.searchByLabel(name, typeIri, 8);
        ctx.lookups.add("search_entities(\"" + name + "\") → " + matches.size() + (matches.size() == 1 ? " match" : " matches"));
        if (matches.size() == 1 || (!matches.isEmpty() && matches.get(0).score >= 0.9
                && (matches.size() == 1 || matches.get(1).score < 0.9))) {
            return matches.get(0);
        }
        ModelOutput o = out("CLARIFY", null, matches.isEmpty()
                ? "No entity named \"" + name + "\" was found."
                : matches.size() + " entities match \"" + name + "\". Which one?");
        o.clarifyKind = "ENTITY";
        for (EntityMatchDto m : matches) o.candidateIris.add(m.iri);
        throw new Stop(o);
    }

    private static boolean sameName(String label, String name) {
        List<String> a = TextNormalizer.tokens(label);
        List<String> b = TextNormalizer.tokens(name);
        if (a.equals(b)) return true;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (TextNormalizer.similarity(a.get(i), b.get(i)) < 0.75) return false;
        return true;
    }

    // =========================
    //  Shapes
    // =========================

    private ModelOutput create(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:create|add|new|creer|cree|creez|ajouter|ajoute|ajoutez)\\s+(?:a |an |un |une |new |nouvelle |nouvel |nouveau )?(?<type>[\\p{L}\\- ]+?)\\s+(?:named|called|nomme|nommee|appele|appelee|qui s'appelle)\\s+(?<name>.+)", mp.folded);
        if (m == null) return null;
        String type = typeFor(ctx, group(mp, m, "type"));
        if (type == null) return null;
        String name = group(mp, m, "name");
        String sparql = "INSERT DATA {\n  GRAPH <" + ctx.internal + "> {\n    <" + PLACEHOLDER + "1> a " + ctx.n(type) + " ;\n      "
                + ctx.n(NAME) + " \"" + esc(name) + "\" .\n  }\n}";
        ModelOutput o = out("UPDATE", ctx.header() + sparql, "Creates a new " + Prefixes.localName(type) + " named \"" + name + "\" (pattern: create).");
        ModelOutput.NewEntity ne = new ModelOutput.NewEntity();
        ne.placeholder = PLACEHOLDER + "1";
        ne.typeIri = type;
        ne.name = name;
        o.newEntities.add(ne);
        o.targetEntityIri = PLACEHOLDER + "1";
        ctx.lookups.add("pattern: create");
        return o;
    }

    private ModelOutput rename(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:rename|renomme|renommer|renommez)\\s+(?<name>.+?)\\s+(?:to|as|into|en)\\s+(?<new>.+)", mp.folded);
        if (m == null) return null;
        ctx.lookups.add("pattern: rename");
        EntityMatchDto e = resolve(ctx, group(mp, m, "name"), null);
        String newName = group(mp, m, "new");
        String g = "GRAPH <" + ctx.internal + ">";
        String sparql = "DELETE { " + g + " { <" + e.iri + "> " + ctx.n(NAME) + " ?old } }\n"
                + "INSERT { " + g + " { <" + e.iri + "> " + ctx.n(NAME) + " \"" + esc(newName) + "\" } }\n"
                + "WHERE { OPTIONAL { " + g + " { <" + e.iri + "> " + ctx.n(NAME) + " ?old } } }";
        String note = "external".equals(e.source)
                ? " Its current name comes from the read-only source " + e.datasourceShortName + ": an internal name is added, the external one stays."
                : "";
        ModelOutput o = out("UPDATE", ctx.header() + sparql, "Renames \"" + e.label + "\" to \"" + newName + "\" (pattern: rename)." + note);
        o.targetEntityIri = e.iri;
        return o;
    }

    private ModelOutput addValue(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:add|ajoute|ajouter|ajoutez)\\s+" + ARTICLE + "(?<prop>[\\p{L}\\- ]+?)\\s+(?<value>'[^']+'|\"[^\"]+\"|«[^»]+»|\\S+)\\s+(?:to|a)\\s+(?<name>.+)", mp.folded);
        if (m == null) return null;
        return propertyValue(ctx, group(mp, m, "prop"), group(mp, m, "value"), group(mp, m, "name"), false);
    }

    private ModelOutput setValue(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:set|change|modify|mets|mettre|modifie|modifier|definis|definir)\\s+" + ARTICLE + "(?<prop>[\\p{L}\\- ]+?)\\s+(?:of|de|du|d')\\s*(?<name>.+?)\\s+(?:to|a|en|=)\\s+(?<value>.+)", mp.folded);
        if (m == null) return null;
        return propertyValue(ctx, group(mp, m, "prop"), group(mp, m, "value"), group(mp, m, "name"), true);
    }

    /** "set/add <property> <value> of/to <entity>", following the precedence rule: data usage, then RiC-O. */
    private ModelOutput propertyValue(Ctx ctx, String propPhrase, String value, String name, boolean replace) {
        ctx.lookups.add(replace ? "pattern: set a property" : "pattern: add a value");
        EntityMatchDto e = resolve(ctx, name, null);
        String predicate = null;
        for (NlClarification c : ctx.clarifications) {
            if ("ENCODING".equals(c.kind) && c.optionId != null && c.optionId.contains("://")) predicate = c.optionId;
        }
        if (predicate == null) predicate = propertyFor(ctx, propPhrase, e.types);
        if (predicate == null) return null;

        String entityType = e.types.stream().filter(t -> ctx.usage(t, NAME) != null || ctx.typeCount(t) > 0).findFirst()
                .orElse(e.types.isEmpty() ? null : e.types.get(0));
        UsageProfileService.PredicateUsage pu = entityType == null ? null : ctx.usage(entityType, predicate);
        RicoDigestService.Entry def = ricoDigest.entry(predicate);
        boolean objectProperty = pu != null ? pu.kinds.stream().anyMatch(k -> k.startsWith("→"))
                : def != null && def.kind == RicoDigestService.Kind.OBJECT_PROPERTY;

        if (pu == null && !ctx.clarifications.stream().anyMatch(c -> "ENCODING".equals(c.kind))) {
            // Never used in this project: ask, rather than guess an encoding.
            ModelOutput o = out("CLARIFY", null, "This project does not record \"" + propPhrase + "\" yet. How should it be recorded?");
            o.clarifyKind = "ENCODING";
            List<String> options = new ArrayList<>();
            options.add(predicate);
            for (String p : ricoDigest.propertiesFor(propPhrase, 4)) if (!options.contains(p) && options.size() < 3) options.add(p);
            for (String p : options) {
                RicoDigestService.Entry d = ricoDigest.entry(p);
                ModelOutput.EncodingOption opt = new ModelOutput.EncodingOption();
                opt.id = p;
                opt.label = Prefixes.curie(p, ctx.profile.prefixes);
                opt.description = d != null && d.definition != null ? d.definition : "";
                opt.basis = d != null ? "RiC-O" : "configuration";
                boolean obj = d != null && d.kind == RicoDigestService.Kind.OBJECT_PROPERTY;
                opt.exampleTriple = "<" + e.label + "> " + opt.label + (obj ? " <" + value + ">" : " \"" + value + "\"");
                opt.statement = "Record " + propPhrase + " with " + opt.label + (obj && d != null && !d.ranges.isEmpty()
                        ? " → a " + Prefixes.curie(d.ranges.get(0), ctx.profile.prefixes) + " entity" : "") + ".";
                o.encodingOptions.add(opt);
            }
            throw new Stop(o);
        }

        String g = "GRAPH <" + ctx.internal + ">";
        String s = "<" + e.iri + ">";
        String p = ctx.n(predicate);
        String delete = replace ? "DELETE { " + g + " { " + s + " " + p + " ?old } }\n" : "";
        String where = replace ? "WHERE { OPTIONAL { " + g + " { " + s + " " + p + " ?old } } }" : "WHERE { }";

        if (!objectProperty) {
            String literal = value;
            if (pu != null) {
                for (UsageProfileService.ValueCount vc : pu.values) {
                    if (!vc.iri && sameName(vc.value, value)) literal = vc.value; // reuse the existing form
                }
            }
            String sparql = delete + "INSERT { " + g + " { " + s + " " + p + " \"" + esc(literal) + "\" } }\n" + where;
            ModelOutput o = out("UPDATE", ctx.header() + sparql, (replace ? "Sets " : "Adds ") + Prefixes.localName(predicate)
                    + " \"" + literal + "\" on \"" + e.label + "\".");
            o.targetEntityIri = e.iri;
            return o;
        }

        // Entity-valued: reuse an existing vocabulary entry, else create one of the property's range.
        String target = null;
        String targetLabel = value;
        if (pu != null) {
            for (UsageProfileService.ValueCount vc : pu.values) {
                if (vc.iri && vc.label != null && sameName(vc.label, value)) {
                    target = vc.value;
                    targetLabel = vc.label;
                }
            }
        }
        if (target == null) {
            List<EntityMatchDto> found = rdfEntityService.searchByLabel(value, null, 5);
            if (!found.isEmpty() && found.get(0).score >= 0.9) {
                target = found.get(0).iri;
                targetLabel = found.get(0).label;
            }
        }
        ModelOutput o;
        if (target != null) {
            String sparql = delete + "INSERT { " + g + " { " + s + " " + p + " <" + target + "> } }\n" + where;
            o = out("UPDATE", ctx.header() + sparql, (replace ? "Sets " : "Adds ") + Prefixes.localName(predicate)
                    + " of \"" + e.label + "\": the existing entry \"" + targetLabel + "\" is reused.");
        } else {
            String range = def != null && !def.ranges.isEmpty() ? def.ranges.get(0)
                    : pu != null ? pu.kinds.stream().filter(k -> k.startsWith("→ ")).map(k -> Prefixes.expand(k.substring(2), ctx.profile.prefixes)).findFirst().orElse(null)
                    : null;
            if (range == null || !range.contains("://")) {
                ModelOutput c = out("CLARIFY", null, "No existing entry matches \"" + value + "\" for " + Prefixes.localName(predicate) + ".");
                c.clarifyKind = "ENTITY";
                if (pu != null) for (UsageProfileService.ValueCount vc : pu.values) if (vc.iri) c.candidateIris.add(vc.value);
                throw new Stop(c);
            }
            String sparql = delete + "INSERT { " + g + " { <" + PLACEHOLDER + "1> a " + ctx.n(range) + " ; " + ctx.n(NAME) + " \"" + esc(value) + "\" .\n    "
                    + s + " " + p + " <" + PLACEHOLDER + "1> } }\n" + where;
            o = out("UPDATE", ctx.header() + sparql, "Creates the " + Prefixes.localName(range) + " \"" + value + "\" and links \"" + e.label
                    + "\" to it with " + Prefixes.localName(predicate) + ".");
            ModelOutput.NewEntity ne = new ModelOutput.NewEntity();
            ne.placeholder = PLACEHOLDER + "1";
            ne.typeIri = range;
            ne.name = value;
            o.newEntities.add(ne);
        }
        o.targetEntityIri = e.iri;
        return o;
    }

    private ModelOutput delete(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:delete|remove|supprime|supprimer|supprimez|efface|effacer|effacez)\\s+" + ARTICLE + "(?<name>.+)", mp.folded);
        if (m == null) return null;
        ctx.lookups.add("pattern: delete");
        EntityMatchDto e = resolve(ctx, group(mp, m, "name"), null);
        if (!"internal".equals(e.source)) {
            return out("UNSUPPORTED", null, "\"" + e.label + "\" comes only from the read-only source "
                    + e.datasourceShortName + ": there is nothing to delete in the project's own data.");
        }
        String g = "GRAPH <" + ctx.internal + ">";
        String sparql = "DELETE { " + g + " { <" + e.iri + "> ?p ?o } } WHERE { " + g + " { <" + e.iri + "> ?p ?o } } ;\n"
                + "DELETE { " + g + " { ?s ?p2 <" + e.iri + "> } } WHERE { " + g + " { ?s ?p2 <" + e.iri + "> } }";
        ModelOutput o = out("UPDATE", sparql, "Deletes \"" + e.label + "\" and the links pointing to it, in the project's own data (pattern: delete).");
        o.targetEntityIri = e.iri;
        return o;
    }

    private static String nameColumn(Ctx ctx) {
        StringBuilder v = new StringBuilder();
        for (String p : RdfEntityService.LABEL_PREDICATES) v.append(ctx.n(p)).append(' ');
        return "OPTIONAL { ?s ?np ?n . VALUES ?np { " + v + "} }";
    }

    private ModelOutput count(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:how many|count|combien(?: de| d'| y a-t-il de| y a t il de)?|nombre (?:de |d'))\\s*(?<type>[\\p{L}\\- ]+?)(?:\\s+(?:are there|is there|there are|y a-t-il|y a t il|il y a))?", mp.folded);
        if (m == null) return null;
        String type = typeFor(ctx, group(mp, m, "type"));
        if (type == null) return null;
        ctx.lookups.add("pattern: count");
        String sparql = "SELECT (COUNT(DISTINCT ?s) AS ?count) WHERE { ?s a " + ctx.n(type) + " }";
        return out("SELECT", ctx.header() + sparql, "Counts the entities of type " + Prefixes.localName(type) + " (pattern: count).");
    }

    private ModelOutput filterNamed(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:(?:list|show|find|search|liste|lister|affiche|afficher|trouve|trouver|cherche|chercher)\\s+)?" + ARTICLE
                + "(?<type>[\\p{L}\\- ]+?)\\s+(?:named|called|nommes?|nommees?|appeles?|appelees?|whose name contains|dont le nom contient)\\s+(?<value>.+)", mp.folded);
        if (m == null) return null;
        String type = typeFor(ctx, group(mp, m, "type"));
        if (type == null) return null;
        ctx.lookups.add("pattern: filter by name");
        String value = group(mp, m, "value");
        String sparql = "SELECT ?s (SAMPLE(?n) AS ?name) WHERE {\n  ?s a " + ctx.n(type) + " .\n  " + nameColumn(ctx)
                + "\n  FILTER(CONTAINS(LCASE(STR(?n)), LCASE(\"" + esc(value) + "\")))\n} GROUP BY ?s ORDER BY ?name LIMIT 200";
        return out("SELECT", ctx.header() + sparql, "Lists the " + Prefixes.localName(type) + " entities whose name contains \"" + value + "\".");
    }

    private ModelOutput filterContains(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:(?:list|show|find|liste|lister|affiche|afficher|trouve|trouver)\\s+)?" + ARTICLE
                + "(?<type>[\\p{L}\\- ]+?)\\s+(?:whose|with|dont|avec)\\s+" + ARTICLE + "(?<prop>[\\p{L}\\- ]+?)\\s+(?:contains|is|contient|est|=)\\s+(?<value>.+)", mp.folded);
        if (m == null) return null;
        String type = typeFor(ctx, group(mp, m, "type"));
        if (type == null) return null;
        String predicate = propertyFor(ctx, group(mp, m, "prop"), List.of(type));
        if (predicate == null) return null;
        ctx.lookups.add("pattern: filter by property");
        String value = group(mp, m, "value");
        String sparql = "SELECT ?s (SAMPLE(?n) AS ?name) (SAMPLE(?v) AS ?value) WHERE {\n  ?s a " + ctx.n(type) + " ; " + ctx.n(predicate) + " ?o .\n"
                + "  OPTIONAL { ?o " + ctx.n(NAME) + " ?oname }\n  BIND(COALESCE(?oname, ?o) AS ?v)\n  " + nameColumn(ctx)
                + "\n  FILTER(CONTAINS(LCASE(STR(?v)), LCASE(\"" + esc(value) + "\")))\n} GROUP BY ?s ORDER BY ?name LIMIT 200";
        return out("SELECT", ctx.header() + sparql, "Lists the " + Prefixes.localName(type) + " entities whose "
                + Prefixes.localName(predicate) + " contains \"" + value + "\".");
    }

    private ModelOutput list(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:(?:list|show|display|give me|liste|lister|listez|affiche|afficher|affichez|montre|montrer|montrez|donne-moi|donne moi)\\s+)?"
                + "(?:la liste des |the list of )?" + ARTICLE + "(?<type>[\\p{L}\\- ]+)", mp.folded);
        if (m == null) return null;
        String type = typeFor(ctx, group(mp, m, "type"));
        if (type == null) return null;
        ctx.lookups.add("pattern: list");
        String sparql = "SELECT ?s (SAMPLE(?n) AS ?name) WHERE {\n  ?s a " + ctx.n(type) + " .\n  " + nameColumn(ctx)
                + "\n} GROUP BY ?s ORDER BY ?name LIMIT 200";
        return out("SELECT", ctx.header() + sparql, "Lists the entities of type " + Prefixes.localName(type) + " with their name (pattern: list).");
    }

    private ModelOutput show(Ctx ctx) {
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);
        Matcher m = match("(?:show|display|open|describe|affiche|afficher|montre|montrer|ouvre|ouvrir|decris|decrire)\\s+(?<name>.+)", mp.folded);
        if (m == null) return null;
        ctx.lookups.add("pattern: show an entity");
        EntityMatchDto e = resolve(ctx, group(mp, m, "name"), null);
        String sparql = "SELECT ?property ?value WHERE { <" + e.iri + "> ?property ?value } ORDER BY ?property";
        ModelOutput o = out("SELECT", sparql, "Shows every property of \"" + e.label + "\".");
        o.targetEntityIri = e.iri;
        return o;
    }

    // =========================
    //  Reusing the history
    // =========================

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.){1,120})\"");

    /**
     * Reuses a past question of the same shape: entity names and quoted values of the old
     * question become slots, matched against the new question; the old query is re-used with
     * the newly resolved IRIs and values. Corrected entries are tried first.
     */
    private ModelOutput fromHistory(Ctx ctx) {
        List<NlHistoryEntry> entries = new ArrayList<>(history.list());
        entries.sort(Comparator.comparing((NlHistoryEntry e) -> !e.edited)); // stable: corrected first, then newest
        String folded = TextNormalizer.fold(ctx.question);
        TextNormalizer.Mapped mp = TextNormalizer.Mapped.of(ctx.question);

        for (NlHistoryEntry e : entries) {
            if (e.question == null || e.sparql == null) continue;
            // An entry that also created vocabulary entries (e.g. a new "masculine" group) is not replayed:
            // the patterns below reuse the entry that now exists instead of creating a duplicate.
            if (e.createdEntityIris != null && e.createdEntityIris.stream().anyMatch(c -> !c.equals(e.targetEntityIri))) continue;
            String oldFolded = TextNormalizer.fold(e.question.trim().replaceAll("[\\s?!.]+$", "").replaceAll("\\s+", " "));

            // slots: entity labels and literal values of the old query that appear in the old question
            record Slot(String oldText, String iri) {}
            List<Slot> slots = new ArrayList<>();
            for (NlHistoryEntry.EntityRef ref : e.entities) {
                if (e.createdEntityIris != null && e.createdEntityIris.contains(ref.iri)) continue; // its name is a value slot
                if (ref.label != null && !ref.label.isBlank() && oldFolded.contains(TextNormalizer.fold(ref.label))) {
                    slots.add(new Slot(ref.label, ref.iri));
                }
            }
            Matcher lit = LITERAL.matcher(e.sparql);
            while (lit.find()) {
                String v = lit.group(1);
                if (oldFolded.contains(TextNormalizer.fold(v)) && slots.stream().noneMatch(s -> TextNormalizer.fold(s.oldText).equals(TextNormalizer.fold(v)))) {
                    slots.add(new Slot(v, null));
                }
            }
            if (slots.isEmpty() && !oldFolded.equals(folded)) continue;

            // a regex of the old question with slots as groups
            List<int[]> spans = new ArrayList<>();
            List<Slot> ordered = new ArrayList<>();
            for (Slot s : slots) {
                int at = oldFolded.indexOf(TextNormalizer.fold(s.oldText));
                if (at >= 0 && spans.stream().noneMatch(sp -> at < sp[1] && at + TextNormalizer.fold(s.oldText).length() > sp[0])) {
                    spans.add(new int[]{at, at + TextNormalizer.fold(s.oldText).length(), ordered.size()});
                    ordered.add(s);
                }
            }
            spans.sort(Comparator.comparingInt(a -> a[0]));
            StringBuilder regex = new StringBuilder();
            int pos = 0;
            List<Slot> groupOrder = new ArrayList<>();
            for (int[] sp : spans) {
                regex.append(Pattern.quote(oldFolded.substring(pos, sp[0]))).append("(.+?)");
                groupOrder.add(ordered.get(sp[2]));
                pos = sp[1];
            }
            regex.append(Pattern.quote(oldFolded.substring(pos)));
            Matcher m = Pattern.compile(regex.toString()).matcher(folded);
            if (!m.matches()) continue;

            String sparql = NlQueryHistoryService.withPlaceholders(e.sparql, e.createdEntityIris);
            List<ModelOutput.NewEntity> newEntities = newEntitiesOf(sparql);
            if (newEntities == null) continue;
            String target = null;
            try {
                for (int i = 0; i < groupOrder.size(); i++) {
                    Slot slot = groupOrder.get(i);
                    String text = mp.original(m.start(i + 1), m.end(i + 1)).trim();
                    if (slot.iri != null) {
                        EntityMatchDto found = resolve(ctx, text, null);
                        sparql = sparql.replace("<" + slot.iri + ">", "<" + found.iri + ">");
                        if (slot.iri.equals(e.targetEntityIri)) target = found.iri;
                    } else {
                        sparql = sparql.replace("\"" + slot.oldText + "\"", "\"" + esc(text) + "\"");
                        for (ModelOutput.NewEntity ne : newEntities) if (slot.oldText.equals(ne.name)) ne.name = text;
                    }
                }
            } catch (Stop stop) {
                throw stop; // an ambiguous or unknown name: ask, as for any other shape
            }
            ModelOutput o = out(e.queryType, sparql, "Same as the earlier question \"" + e.question + "\", with the new names and values"
                    + (e.edited ? " (a query corrected by the user)." : "."));
            o.newEntities = newEntities;
            o.targetEntityIri = !newEntities.isEmpty() ? newEntities.get(0).placeholder : target != null ? target : "";
            ctx.lookups.add("reused history entry of " + (e.timestamp != null && e.timestamp.length() >= 10 ? e.timestamp.substring(0, 10) : "?")
                    + ": \"" + e.question + "\"");
            return o;
        }
        return null;
    }

    /** Placeholders of a query with their type (from "<placeholder> a <T>"); null if a type can't be found. */
    private List<ModelOutput.NewEntity> newEntitiesOf(String sparql) {
        List<ModelOutput.NewEntity> out = new ArrayList<>();
        Matcher m = Pattern.compile("<(" + Pattern.quote(PLACEHOLDER) + "\\d+)>").matcher(sparql);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) seen.add(m.group(1));
        for (String ph : seen) {
            Matcher t = Pattern.compile("<" + Pattern.quote(ph) + ">\\s+(?:a|rdf:type|<http://www\\.w3\\.org/1999/02/22-rdf-syntax-ns#type>)\\s+(<[^>]+>|[\\w-]+:[\\w-]+)").matcher(sparql);
            if (!t.find()) return null;
            String type = t.group(1);
            ModelOutput.NewEntity ne = new ModelOutput.NewEntity();
            ne.placeholder = ph;
            ne.typeIri = type.startsWith("<") ? type.substring(1, type.length() - 1) : prefixes.expand(type);
            Matcher n = Pattern.compile("<" + Pattern.quote(ph) + ">[^.]*?(?:rico:name|<" + Pattern.quote(NAME) + ">)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(sparql);
            ne.name = n.find() ? n.group(1) : "";
            out.add(ne);
        }
        return out;
    }
}
