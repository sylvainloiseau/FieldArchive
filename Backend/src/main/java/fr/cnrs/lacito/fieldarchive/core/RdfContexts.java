package fr.cnrs.lacito.fieldarchive.core;
/**
 * An RDF context is a named graph,
 * corresponding to a DataSource
 * (InternalDataSource or ExternalDataSource) or
 * to a spacial named graph, hidden to the user,
 * that contains the properties of the ProjectContext
 * (such as the definition of the ExternalDataSource: name, description, etc.)
 */
public final class RdfContexts {
    private RdfContexts() {}

    /**
     * The IRI of the named graph that contains the properties of the ProjectContext
     * (such as the definition of the ExternalDataSource, the project description and name, etc.)
     */
    public static final String CTX_META = RdfNamespaces.APP + "/context/metadata";
    public static final String CTX_ONTO_RICO = RdfNamespaces.APP + "/context/ontology/rico";
}
