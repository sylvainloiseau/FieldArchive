import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { OntologyLabelsService } from './ontology-labels.service';

@Injectable({
  providedIn: 'root'
})
export class GestionRessourcesService {

  private apiUrl = 'http://localhost:8080/sparql';
  private ontologyUrl = 'http://localhost:8080/ontology';
  private rdfUrl = 'http://localhost:8080/rdf';

  constructor(
    private http: HttpClient,
    private ontologyLabelsService: OntologyLabelsService
  ) {}

  /**
   * Retourne les types regroupés par label d'ontologie
   */
  getOntologyLabel(listeIris: string[]): any[] {
    const ontology_types: any[] = [];
    const labels = this.ontologyLabelsService.getLabels();

    for (let typeIri of listeIris) {

      const ns = typeIri.includes('#')
        ? typeIri.substring(0, typeIri.lastIndexOf('#') + 1)
        : typeIri.substring(0, typeIri.lastIndexOf('/') + 1);

      const elt = ns in labels ? labels[ns] : ns;

      let obj = ontology_types.find(o => elt in o);

      if (!obj) {
        obj = { [elt]: [] };
        ontology_types.push(obj);
      }

      const localName = typeIri.includes('#')
        ? typeIri.substring(typeIri.lastIndexOf('#') + 1)
        : typeIri.substring(typeIri.lastIndexOf('/') + 1);

      obj[elt].push(localName);
    }

    return ontology_types;
  }

  /**
   * Version simplifiée : retourne uniquement les labels
   */
  getOntologyLabel_v2(listeIris: string[]): string[] {
    const labelsMap = this.ontologyLabelsService.getLabels();

    return listeIris.map(typeIri => {
      const ns = typeIri.includes('#')
        ? typeIri.substring(0, typeIri.lastIndexOf('#') + 1)
        : typeIri.substring(0, typeIri.lastIndexOf('/') + 1);

      return ns in labelsMap ? labelsMap[ns] : ns;
    });
  }

  /**
   * Récupère les types depuis le backend
   */
  getTypes(): Observable<any> {
    return this.http.get<any>(`${this.ontologyUrl}/types`);
  }

  /**
   * Récupère l'URL d'une ontologie à partir de son label
   */
  getTypeUrlByName(ontologyName: string): string {
    const labels = this.ontologyLabelsService.getLabels();

    const entry = Object.entries(labels)
      .find(([_, label]) => label === ontologyName);

    return entry ? entry[0] : '';
  }

  /**
   * Récupère les entités par type
   */
  getAllEntitiesByType(typeUrl: string): Observable<any> {
    const params = new HttpParams().set('type', typeUrl);
    return this.http.get<any>(`${this.rdfUrl}/entities`, { params });
  }

  /**
   * Détails d'une entité
   */
  getEntityDetails(entityKey: string): Observable<any> {
    const params = new HttpParams().set('key', entityKey);
    return this.http.get<any>(`${this.rdfUrl}/entity`, { params });
  }

  createEntity(entity: any): Observable<any> {
    return this.http.post<any>(`${this.rdfUrl}/entities`, entity);
  }

  /**
   * Supprimer une entité
   */
  deleteEntity(entityKey: string): Observable<any> {
    const params = new HttpParams().set('key', entityKey);
    return this.http.delete<any>(`${this.rdfUrl}/entity`, { params });
  }

  /**
   * Modifier une entité
   */
  editEntity(entityKey: string, newEntity: any): Observable<any> {
    const params = new HttpParams().set('key', entityKey);
    return this.http.put<any>(`${this.rdfUrl}/entity`, newEntity, { params });
  }

  getAllRicoClasses(): Observable<any> {
    const objt = {
      "query": "PREFIX owl: <http://www.w3.org/2002/07/owl#> PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> SELECT ?type ?label WHERE { GRAPH <http://uspn.fr/app#context/ontology/rico> { { ?type a owl:Class . } UNION { ?type a rdfs:Class . } FILTER(STRSTARTS(STR(?type), \"https://www.ica.org/standards/RiC/ontology#\")) OPTIONAL { ?type rdfs:label ?label . FILTER(lang(?label) = \"\" || langMatches(lang(?label), \"en\") || langMatches(lang(?label), \"fr\")) } } } ORDER BY ?type"
    };
    return this.http.post<any>(`${this.apiUrl}/select`, objt);
  }

  getPredicatesByTypeRico(type: string): Observable<any[]> {
    const objt = {
      query: `PREFIX rico: <https://www.ica.org/standards/RiC/ontology#> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> PREFIX owl: <http://www.w3.org/2002/07/owl#> SELECT DISTINCT ?p ?label ?domain ?range ?valueKind WHERE { GRAPH <http://uspn.fr/app#context/ontology/rico> { BIND(rico:Person AS ?selectedClass) ?selectedClass rdfs:subClassOf* ?domain . ?p rdfs:domain ?domain . FILTER(STRSTARTS(STR(?p), "https://www.ica.org/standards/RiC/ontology#")) OPTIONAL { ?p rdfs:label ?label . FILTER(lang(?label) = "" || langMatches(lang(?label), "en") || langMatches(lang(?label), "fr")) } OPTIONAL { ?p rdfs:range ?range . } BIND(IF(EXISTS { ?p a owl:ObjectProperty . }, "iri", IF(EXISTS { ?p a owl:DatatypeProperty . }, "literal", "unknown")) AS ?valueKind) } } ORDER BY ?p`
    };

    return this.http.post<any[]>(`${this.apiUrl}/select`, objt);
  }

  getPredicatesByType_V2(type: string): Observable<any[]> {
    const query = `
      SELECT DISTINCT ?p
      WHERE {
        ?s rdf:type <${type}> .
        ?s ?p ?o .
      }
    `;

    return this.http.post<any[]>(`${this.apiUrl}/select`, {
      query: query
    });
  }

}



