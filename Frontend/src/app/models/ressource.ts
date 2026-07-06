export  interface Entity {
    id: string;
    titre: string;
    date: string;
    source: string;
    statut: 'complet' | 'partiel';
    type: 'Event' | 'Person' | 'Record' | 'Instantiation' | 'Agent' | 'Place' | 'Record Resource';
    birthDate?: string;
    deathDate?: string;
    associatedWith?: any[];
    associations? : Association[];
}

export interface EntityType {
  name: string;
  icon: string;
  count: number;
  type: Entity['type'];
}

export interface Association {
    predicate : string;
    object : string;
}

