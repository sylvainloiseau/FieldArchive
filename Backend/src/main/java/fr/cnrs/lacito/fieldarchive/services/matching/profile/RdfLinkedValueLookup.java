package fr.cnrs.lacito.fieldarchive.services.matching.profile;

import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.DateInterval;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.PartialDate;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

import java.util.*;

/**
 * {@link LinkedValueLookup} reading the data graphs of the open project, with a cache for the
 * duration of one run. Text: the label predicates, then {@code rico:textualValue} (a
 * {@code rico:Name}, {@code rico:Title}…), then {@code rico:expressedDate}. Date: a
 * {@code rico:Date}'s normalized value, else its expressed date, else its beginning/end dates.
 */
public final class RdfLinkedValueLookup implements LinkedValueLookup {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final List<IRI> TEXT_PREDICATES;
    static {
        List<IRI> l = new ArrayList<>();
        for (String p : RdfEntityService.LABEL_PREDICATES) l.add(vf.createIRI(p));
        l.add(vf.createIRI(RdfNamespaces.RICO, "textualValue"));
        l.add(vf.createIRI(RdfNamespaces.RICO, "expressedDate"));
        TEXT_PREDICATES = List.copyOf(l);
    }
    private static final IRI NORMALIZED_DATE = vf.createIRI(RdfNamespaces.RICO, "normalizedDateValue");
    private static final IRI EXPRESSED_DATE = vf.createIRI(RdfNamespaces.RICO, "expressedDate");
    private static final IRI BEGINNING = vf.createIRI(RdfNamespaces.RICO, "hasBeginningDate");
    private static final IRI END = vf.createIRI(RdfNamespaces.RICO, "hasEndDate");
    private static final IRI BEGINNING_LITERAL = vf.createIRI(RdfNamespaces.RICO, "beginningDate");
    private static final IRI END_LITERAL = vf.createIRI(RdfNamespaces.RICO, "endDate");

    private final RepositoryConnection conn;
    private final Resource[] graphs;
    private final Map<String, Optional<String>> texts = new HashMap<>();
    private final Map<String, Optional<DateInterval>> dates = new HashMap<>();

    public RdfLinkedValueLookup(RepositoryConnection conn, Collection<IRI> dataGraphs) {
        this.conn = conn;
        this.graphs = dataGraphs.toArray(new Resource[0]);
    }

    @Override
    public Optional<String> textOf(String iri) {
        return texts.computeIfAbsent(iri, k -> {
            IRI subject = vf.createIRI(k);
            for (IRI p : TEXT_PREDICATES) {
                Optional<String> v = firstLiteral(subject, p);
                if (v.isPresent()) return v;
            }
            return Optional.empty();
        });
    }

    @Override
    public Optional<DateInterval> dateOf(String iri) {
        return dates.computeIfAbsent(iri, k -> {
            IRI subject = vf.createIRI(k);
            Optional<DateInterval> d = firstLiteral(subject, NORMALIZED_DATE).flatMap(PartialDate::parse);
            if (d.isEmpty()) d = firstLiteral(subject, EXPRESSED_DATE).flatMap(PartialDate::parse);
            if (d.isPresent()) return d;
            Optional<DateInterval> begin = boundary(subject, BEGINNING_LITERAL, BEGINNING);
            Optional<DateInterval> end = boundary(subject, END_LITERAL, END);
            if (begin.isPresent() && end.isPresent() && !begin.get().start().isAfter(end.get().end())) {
                return Optional.of(new DateInterval(begin.get().start(), end.get().end(), false));
            }
            return begin.or(() -> end);
        });
    }

    private Optional<DateInterval> boundary(IRI subject, IRI literalPredicate, IRI entityPredicate) {
        Optional<DateInterval> d = firstLiteral(subject, literalPredicate).flatMap(PartialDate::parse);
        if (d.isPresent()) return d;
        try (RepositoryResult<Statement> st = conn.getStatements(subject, entityPredicate, null, false, graphs)) {
            for (Statement s : st) {
                if (s.getObject() instanceof IRI date && !date.equals(subject)) {
                    Optional<DateInterval> nested = firstLiteral(date, NORMALIZED_DATE).flatMap(PartialDate::parse);
                    if (nested.isEmpty()) nested = firstLiteral(date, EXPRESSED_DATE).flatMap(PartialDate::parse);
                    if (nested.isPresent()) return nested;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> firstLiteral(IRI subject, IRI predicate) {
        try (RepositoryResult<Statement> st = conn.getStatements(subject, predicate, null, false, graphs)) {
            for (Statement s : st) {
                if (s.getObject() instanceof Literal l && !l.getLabel().isBlank()) return Optional.of(l.getLabel());
            }
        }
        return Optional.empty();
    }
}
