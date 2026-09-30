package fr.cnrs.lacito.fieldarchive.services.nlquery;

import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.InternalDataBookkeeping;
import fr.cnrs.lacito.fieldarchive.services.SparqlService;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.*;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;
import org.eclipse.rdf4j.query.parser.ParsedOperation;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLUpdateDataBlockParser;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;

import java.io.StringReader;
import java.util.*;
import java.util.function.Predicate;

/**
 * Checks a generated query before it is shown to the user (decisions 4 and 10 of the plan):
 * the operation matches the announced type; an UPDATE writes only into the internal graph,
 * with explicit GRAPH clauses (no WITH), never LOAD / CLEAR / DROP / CREATE / COPY / MOVE / ADD,
 * never the bookkeeping triples; and every concrete subject it writes exists.
 */
public final class SparqlGuard {

    private SparqlGuard() {}

    public static final class Result {
        public final Set<String> writtenSubjects = new LinkedHashSet<>();
        public final Set<String> placeholdersUsed = new LinkedHashSet<>();
    }

    public static Result check(String sparql, String queryType, IRI internalGraph, Predicate<String> exists) {
        ParsedOperation op;
        try {
            op = SparqlService.parse(sparql);
        } catch (BadRequestException e) {
            throw new GuardException(e.getMessage());
        }
        Result result = new Result();
        collectPlaceholders(sparql, result);

        if ("SELECT".equals(queryType)) {
            if (!(op instanceof ParsedTupleQuery)) {
                throw new GuardException("queryType is SELECT but the query is not a SELECT (ASK, CONSTRUCT and DESCRIBE are not supported).");
            }
            return result;
        }
        if (!(op instanceof ParsedUpdate update)) {
            throw new GuardException("queryType is UPDATE but the query is not a SPARQL UPDATE.");
        }

        for (UpdateExpr expr : update.getUpdateExprs()) {
            if (expr instanceof Load || expr instanceof Clear || expr instanceof Create
                    || expr instanceof Copy || expr instanceof Move || expr instanceof Add) {
                throw new GuardException(expr.getClass().getSimpleName().toUpperCase(Locale.ROOT)
                        + " is not allowed. Use INSERT / DELETE on the internal graph only.");
            }
            Dataset ds = update.getDatasetMapping().get(expr);
            if (ds != null && (ds.getDefaultInsertGraph() != null || !ds.getDefaultRemoveGraphs().isEmpty())) {
                throw new GuardException("WITH is not allowed: write GRAPH <" + internalGraph + "> { … } explicitly in the INSERT and DELETE templates.");
            }
            if (expr instanceof Modify m) {
                for (TupleExpr template : Arrays.asList(m.getDeleteExpr(), m.getInsertExpr())) {
                    if (template == null) continue;
                    for (StatementPattern sp : StatementPatternCollector.process(template)) {
                        checkQuad(sp.getSubjectVar(), sp.getPredicateVar(), sp.getContextVar(), internalGraph, result);
                    }
                }
            } else if (expr instanceof InsertData d) {
                checkDataBlock(d.getDataBlock(), internalGraph, result);
            } else if (expr instanceof DeleteData d) {
                checkDataBlock(d.getDataBlock(), internalGraph, result);
            }
        }

        for (String s : result.writtenSubjects) {
            if (s.startsWith(NlQueryHistoryService.PLACEHOLDER_PREFIX)) continue;
            if (!exists.test(s)) {
                throw new GuardException("<" + s + "> does not exist in the project. Look entities up with search_entities instead of writing IRIs, and use <"
                        + NlQueryHistoryService.PLACEHOLDER_PREFIX + "N> placeholders for new entities.");
            }
        }
        return result;
    }

    private static void checkQuad(Var subject, Var predicate, Var context, IRI internalGraph, Result result) {
        if (context == null || !context.hasValue()) {
            throw new GuardException("Every INSERT / DELETE template must be inside GRAPH <" + internalGraph
                    + "> { … }: writes are allowed only in the project's internal graph.");
        }
        if (!internalGraph.equals(context.getValue())) {
            throw new GuardException("Writing into <" + context.getValue().stringValue() + "> is not allowed: only the internal graph <"
                    + internalGraph + "> can be changed (external data sources are read-only).");
        }
        checkPredicate(predicate.hasValue() ? predicate.getValue().stringValue() : null);
        if (subject.hasValue() && subject.getValue() instanceof IRI s) result.writtenSubjects.add(s.stringValue());
    }

    private static void checkPredicate(String predicate) {
        if (InternalDataBookkeeping.DCTERMS_CREATED.equals(predicate) || InternalDataBookkeeping.DCTERMS_MODIFIED.equals(predicate)) {
            throw new GuardException("Do not write dcterms:created or dcterms:modified: the application maintains them.");
        }
    }

    private static void checkDataBlock(String block, IRI internalGraph, Result result) {
        SPARQLUpdateDataBlockParser parser = new SPARQLUpdateDataBlockParser(SimpleValueFactory.getInstance());
        StatementCollector collector = new StatementCollector();
        parser.setRDFHandler(collector);
        try {
            parser.parse(new StringReader(block), "");
        } catch (Exception e) {
            throw new GuardException("Cannot read the INSERT DATA / DELETE DATA block: " + e.getMessage());
        }
        for (Statement st : collector.getStatements()) {
            Resource ctx = st.getContext();
            if (ctx == null) {
                throw new GuardException("INSERT DATA / DELETE DATA must be inside GRAPH <" + internalGraph + "> { … }.");
            }
            if (!internalGraph.equals(ctx)) {
                throw new GuardException("Writing into <" + ctx.stringValue() + "> is not allowed: only the internal graph <"
                        + internalGraph + "> can be changed.");
            }
            checkPredicate(st.getPredicate().stringValue());
            if (st.getSubject() instanceof IRI s) result.writtenSubjects.add(s.stringValue());
        }
    }

    private static void collectPlaceholders(String sparql, Result result) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(" + java.util.regex.Pattern.quote(NlQueryHistoryService.PLACEHOLDER_PREFIX) + "\\d+)>").matcher(sparql);
        while (m.find()) result.placeholdersUsed.add(m.group(1));
    }

    /** A validation failure, sent back to the model so it can fix its query. */
    public static class GuardException extends RuntimeException {
        public GuardException(String message) { super(message); }
    }
}
