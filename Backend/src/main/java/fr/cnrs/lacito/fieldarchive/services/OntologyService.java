package fr.cnrs.lacito.fieldarchive.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.OntologyLabelDto;
import fr.cnrs.lacito.fieldarchive.dtos.SaveOntologyLabelRequest;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OntologyService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private IRI metaCtx() {
        return vf.createIRI(RdfContexts.CTX_META);
    }

    private IRI ontologyType() {
        return vf.createIRI(RdfNamespaces.APP, "OntologyNamespace");
    }

    public OntologyService() {
        loadConfig();
    }

    private Map<String, Object> ontologyConfig;

    private static String extractPrefix(String uri) {
        if (uri.contains("#")) {
            return uri.substring(0, uri.indexOf("#"));
        } else {
            int lastSlash = uri.lastIndexOf("/");
            return (lastSlash != -1) ? uri.substring(0, lastSlash) : uri;
        }
    }
    private static String extractType(String uri) {
        if (uri.contains("#")) {
            return uri.substring(uri.indexOf("#") +1);
        } else {
            int lastSlash = uri.lastIndexOf("/");
            return (lastSlash != -1) ? uri.substring(lastSlash+1) : uri;
        }
    }

    private void loadConfig() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream is = new ClassPathResource("ontologies/configuration.json").getInputStream();

            ontologyConfig = mapper.readValue(is, Map.class);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private void requireProjectOpen() {
        if (!ProjectContext.isOpen()) {
            throw new BadRequestException("Aucun projet ouvert.");
        }
    }

    private IRI toOntologyIri(String url) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("L'URL de l'ontologie est obligatoire.");
        }

        try {
            return vf.createIRI(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("URL invalide : " + url);
        }
    }

    private String cleanLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new BadRequestException("Le label est obligatoire.");
        }
        return label.trim();
    }

    private String getLabel(RepositoryConnection conn, IRI ontology, IRI ctxMeta) {
        try (RepositoryResult<Statement> stmts =
                     conn.getStatements(ontology, RDFS.LABEL, null, false, ctxMeta)) {
            if (stmts.hasNext()) {
                Value v = stmts.next().getObject();
                return v.stringValue();
            }
        }
        return ontology.stringValue();
    }

    // =============================
    // ALL TYPES GROUPED BY ONTOLOGY
    // =============================
    public Map<String, Object> getAllTypes() {

        String query = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

        SELECT DISTINCT ?type WHERE {
            ?s rdf:type ?type .
        }
        """;

        List<String> types = new ArrayList<>();

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {

                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    Value type = binding.getValue("type");
                    types.add(type.stringValue());
                }
            }
        }

        Map<String, List<String>> grouped = types.stream()
                .collect(Collectors.groupingBy(
                        OntologyService::extractPrefix,
                        Collectors.mapping(
                                OntologyService::extractType,
                                Collectors.toList()
                        )
                ));

        // print
        grouped.forEach((prefix, list) -> {
            System.out.println(prefix + " :");
            list.forEach(v -> System.out.println("   - " + v));
        });

        Map<String, Object> ontologies =
                (Map<String, Object>) ontologyConfig.get("ontologies");


        Map<String, Object> data = new HashMap<>();

        grouped.forEach((prefix, list) -> {
            Map<String, Object> ontologyData = (Map<String, Object>) ontologies.get(prefix);
            if (ontologyData != null) {

                // A copy of the data in the json config file
                Map<String, Object> merged = new HashMap<>(ontologyData);

                Map<String, Object> usedTypes = new HashMap<>();
                usedTypes.put("name", "Used Types" );
                usedTypes.put("value", list);

                merged.put("usedTypes", usedTypes);

                data.put(prefix, merged);
            }
        });

        return data;
    }

    // =============================
    // PROPRIETES D'UN TYPE
    // =============================
    public List<String> getPropertiesOfType(String type) {

        String query =
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                        "SELECT DISTINCT ?p WHERE { " +
                        " ?s rdf:type <" + type + "> . " +
                        " ?s ?p ?o . " +
                        "}";

        List<String> properties = new ArrayList<>();

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {

                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    properties.add(binding.getValue("p").stringValue());
                }
            }
        }

        return properties;
    }

    // =============================
    // RESSOURCES D'UN TYPE
    // =============================
    public List<String> getResourcesOfType(String type) {

        String query =
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                        "SELECT DISTINCT ?s WHERE { " +
                        " ?s rdf:type <" + type + "> . " +
                        "}";

        List<String> resources = new ArrayList<>();

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {

                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    resources.add(binding.getValue("s").stringValue());
                }
            }
        }

        return resources;
    }

    // =========================================================
    // NOUVEAU : LISTER LES ONTOLOGIES STOCKEES EN METADONNEES
    // =========================================================
    public List<OntologyLabelDto> getOntologyLabels() {
        requireProjectOpen();

        List<OntologyLabelDto> out = new ArrayList<>();
        IRI ctxMeta = metaCtx();
        IRI type = ontologyType();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            try (RepositoryResult<Statement> stmts =
                         conn.getStatements(null, RDF.TYPE, type, false, ctxMeta)) {

                while (stmts.hasNext()) {
                    Statement st = stmts.next();

                    if (st.getSubject() instanceof IRI ontology) {
                        out.add(new OntologyLabelDto(
                                ontology.stringValue(),
                                getLabel(conn, ontology, ctxMeta)
                        ));
                    }
                }
            }
        }

        return out;
    }

    // =========================================================
    // NOUVEAU : AJOUTER UNE ONTOLOGIE DANS CTX_META
    // =========================================================
    public void addOntologyLabel(SaveOntologyLabelRequest request) {
        requireProjectOpen();

        if (request == null) {
            throw new BadRequestException("Requête invalide.");
        }

        IRI ontology = toOntologyIri(request.getUrl());
        String label = cleanLabel(request.getLabel());
        IRI ctxMeta = metaCtx();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (conn.hasStatement(ontology, RDF.TYPE, ontologyType(), false, ctxMeta)) {
                throw new BadRequestException("Cette ontologie existe déjà : " + ontology.stringValue());
            }

            conn.begin();
            conn.add(ontology, RDF.TYPE, ontologyType(), ctxMeta);
            conn.add(ontology, RDFS.LABEL, vf.createLiteral(label), ctxMeta);
            conn.commit();
        }
    }

    // =========================================================
    // NOUVEAU : MODIFIER LE LABEL D'UNE ONTOLOGIE
    // =========================================================
    public void updateOntologyLabel(SaveOntologyLabelRequest request) {
        requireProjectOpen();

        if (request == null) {
            throw new BadRequestException("Requête invalide.");
        }

        IRI ontology = toOntologyIri(request.getUrl());
        String label = cleanLabel(request.getLabel());
        IRI ctxMeta = metaCtx();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ontology, RDF.TYPE, ontologyType(), false, ctxMeta)) {
                throw new NotFoundException("Ontologie introuvable : " + ontology.stringValue());
            }

            conn.begin();
            conn.remove(ontology, RDFS.LABEL, null, ctxMeta);
            conn.add(ontology, RDFS.LABEL, vf.createLiteral(label), ctxMeta);
            conn.commit();
        }
    }

    // =========================================================
    // NOUVEAU : SUPPRIMER UNE ONTOLOGIE
    // =========================================================
    public void deleteOntologyLabel(String url) {
        requireProjectOpen();

        IRI ontology = toOntologyIri(url);
        IRI ctxMeta = metaCtx();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ontology, RDF.TYPE, ontologyType(), false, ctxMeta)) {
                throw new NotFoundException("Ontologie introuvable : " + ontology.stringValue());
            }

            conn.begin();
            conn.remove(ontology, null, null, ctxMeta);
            conn.commit();
        }
    }

}