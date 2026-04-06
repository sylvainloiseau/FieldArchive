export type OntologyLabels = Record<string, string>;

export let ONTOLOGY_LABELS: OntologyLabels = {
  "https://www.ica.org/standards/RiC/ontology#": "rico",
  "http://uspn.fr/app#": "Application",
  "http://www.w3.org/2002/07/owl#": "OWL",
  "http://www.w3.org/2000/01/rdf-schema#": "RDFS",
  "http://purl.org/dc/terms/": "Dublin Core"
};

export function getOntologyList(): { name: string; iri: string }[] {
  return Object.entries(ONTOLOGY_LABELS).map(([iri, name]) => ({ name, iri }));
}