package fr.cnrs.lacito.fieldarchive.services.matching.profile;

import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfiguration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads the profiles of the entities of one data source from its named graph only: types,
 * label, and the values of the predicates the configuration compares. Blank-node subjects are
 * ignored (they cannot be linked durably).
 */
public final class EntityProfileLoader {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private EntityProfileLoader() {}

    public static EntityProfiles loadProfiles(RepositoryConnection conn, String dataSource, IRI graph,
                                              MatchingConfiguration configuration) {
        EntityProfiles out = new EntityProfiles(dataSource);
        Map<String, EntityProfile> byIri = new HashMap<>();

        try (RepositoryResult<Statement> st = conn.getStatements(null, RDF.TYPE, null, false, graph)) {
            for (Statement s : st) {
                if (s.getSubject() instanceof IRI subject && s.getObject() instanceof IRI type) {
                    byIri.computeIfAbsent(subject.stringValue(), k -> new EntityProfile(new EntityNode(dataSource, k)))
                            .types().add(type.stringValue());
                }
            }
        }
        for (String predicate : predicatesToRead(configuration)) {
            try (RepositoryResult<Statement> st = conn.getStatements(null, vf.createIRI(predicate), null, false, graph)) {
                for (Statement s : st) {
                    if (s.getSubject() instanceof IRI subject) {
                        byIri.computeIfAbsent(subject.stringValue(), k -> new EntityProfile(new EntityNode(dataSource, k)))
                                .addValue(predicate, s.getObject());
                    }
                }
            }
        }
        byIri.values().stream()
                .sorted((x, y) -> x.iri().compareTo(y.iri()))
                .forEach(p -> {
                    p.setLabel(labelOf(p));
                    out.add(p);
                });
        return out;
    }

    /** One entity's profile in one graph, or null when the graph says nothing about it. */
    public static EntityProfile loadProfile(RepositoryConnection conn, String dataSource, IRI graph, String iri,
                                            MatchingConfiguration configuration) {
        IRI subject = vf.createIRI(iri);
        if (!conn.hasStatement(subject, null, null, false, graph)) return null;
        EntityProfile p = new EntityProfile(new EntityNode(dataSource, iri));
        try (RepositoryResult<Statement> st = conn.getStatements(subject, null, null, false, graph)) {
            Set<String> wanted = predicatesToRead(configuration);
            for (Statement s : st) {
                String predicate = s.getPredicate().stringValue();
                if (s.getPredicate().equals(RDF.TYPE) && s.getObject() instanceof IRI type) {
                    p.types().add(type.stringValue());
                } else if (wanted.contains(predicate)) {
                    p.addValue(predicate, s.getObject());
                }
            }
        }
        p.setLabel(labelOf(p));
        return p;
    }

    private static Set<String> predicatesToRead(MatchingConfiguration configuration) {
        Set<String> out = new LinkedHashSet<>(configuration.allPredicates());
        out.addAll(RdfEntityService.LABEL_PREDICATES);
        out.remove(RDF.TYPE.stringValue());
        return out;
    }

    private static String labelOf(EntityProfile p) {
        for (String predicate : RdfEntityService.LABEL_PREDICATES) {
            for (var v : p.valuesOf(predicate)) {
                if (v instanceof Literal l && !l.getLabel().isBlank()) return l.getLabel();
            }
        }
        return null;
    }
}
