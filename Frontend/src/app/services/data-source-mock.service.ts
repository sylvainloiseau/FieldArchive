// // src/app/services/data-source-mock.service.ts

// import { Injectable } from '@angular/core';
// import { Observable, of, delay, BehaviorSubject } from 'rxjs';
// import { tap } from 'rxjs/operators';
// import { DataSource, CreateDataSourceRequest } from '../models/data-source.model';

// @Injectable({
//   providedIn: 'root'
// })
// export class DataSourceMockService {
//   private dataSourcesSubject = new BehaviorSubject<DataSource[]>([]);
//   public dataSources$ = this.dataSourcesSubject.asObservable();

//   private mockData: DataSource[] = [
//     {
//       id: '1',
//       shortName: 'archive-2024',
//       name: 'Research Archive 2024',
//       description: 'Main research data collection from field studies in Morocco',
//       sourceType: 'INTERNAL',
//       createdDate: new Date('2024-01-15'),
//       graphIRI: 'urn:datasource:archive-2024'
//     },
//     {
//       id: '2',
//       shortName: 'tropy-photos',
//       name: 'Tropy Photo Archive',
//       description: 'Historical photographs from anthropological field research',
//       sourceType: 'EXTERNAL',
//       tool: 'Tropy',
//       url: 'https://example.com/tropy/data.ttl',
//       createdDate: new Date('2024-02-01'),
//       lastSyncDate: new Date('2024-02-03'),
//       graphIRI: 'urn:datasource:tropy-photos'
//     },
//     {
//       id: '3',
//       shortName: 'lameta-recordings',
//       name: 'Audio Recordings Collection',
//       description: 'Field recordings and linguistic data',
//       sourceType: 'EXTERNAL',
//       tool: 'Lameta',
//       url: '/data/lameta/recordings.rdf',
//       createdDate: new Date('2024-01-20'),
//       lastSyncDate: new Date('2024-01-25'),
//       graphIRI: 'urn:datasource:lameta-recordings'
//     }
//   ];

//   constructor() {
//     // Initialize the subject with mock data
//     this.dataSourcesSubject.next([...this.mockData]);
//   }

//   getAllDataSources(): Observable<DataSource[]> {
//     console.log('📦 [MOCK] Fetching all data sources...');
//     return of([...this.mockData]).pipe(
//       delay(500),
//       tap(sources => {
//         console.log(`✅ [MOCK] Loaded ${sources.length} data sources`);
//         this.dataSourcesSubject.next(sources);
//       })
//     );
//   }

//   getDataSourceById(id: string): Observable<DataSource> {
//     console.log(`🔍 [MOCK] Fetching data source with id: ${id}`);
//     const source = this.mockData.find(s => s.id === id);
//     return of(source!).pipe(
//       delay(300),
//       tap(source => console.log(`✅ [MOCK] Found:`, source))
//     );
//   }

//   createDataSource(data: CreateDataSourceRequest): Observable<DataSource> {
//     console.log('➕ [MOCK] Creating new data source:', data);
    
//     const newSource: DataSource = {
//       id: String(this.mockData.length + 1),
//       ...data,
//       createdDate: new Date(),
//       graphIRI: `urn:datasource:${data.shortName}`
//     };

//     if (data.sourceType === 'EXTERNAL') {
//       newSource.lastSyncDate = new Date();
//     }

//     this.mockData.push(newSource);
    
//     return of(newSource).pipe(
//       delay(500),
//       tap(created => {
//         console.log('✅ [MOCK] Created:', created);
//         this.dataSourcesSubject.next([...this.mockData]);
//       })
//     );
//   }

//   updateDataSource(id: string, data: Partial<DataSource>): Observable<DataSource> {
//     console.log(`📝 [MOCK] Updating data source ${id}:`, data);
    
//     const index = this.mockData.findIndex(s => s.id === id);
//     if (index !== -1) {
//       this.mockData[index] = { ...this.mockData[index], ...data };
//       return of(this.mockData[index]).pipe(
//         delay(500),
//         tap(updated => {
//           console.log('✅ [MOCK] Updated:', updated);
//           this.dataSourcesSubject.next([...this.mockData]);
//         })
//       );
//     }
//     return of({} as DataSource);
//   }

//   deleteDataSource(id: string): Observable<void> {
//     console.log(`🗑️ [MOCK] Deleting data source ${id}`);
    
//     const index = this.mockData.findIndex(s => s.id === id);
//     if (index !== -1) {
//       const deleted = this.mockData.splice(index, 1)[0];
//       console.log('✅ [MOCK] Deleted:', deleted.name);
//       this.dataSourcesSubject.next([...this.mockData]);
//     }
    
//     return of(void 0).pipe(delay(500));
//   }

//   syncExternalSource(id: string): Observable<any> {
//     console.log(`🔄 [MOCK] Synchronizing external source ${id}`);
    
//     const index = this.mockData.findIndex(s => s.id === id);
//     if (index !== -1 && this.mockData[index].sourceType === 'EXTERNAL') {
//       this.mockData[index].lastSyncDate = new Date();
//       console.log('✅ [MOCK] Synchronized successfully');
//       this.dataSourcesSubject.next([...this.mockData]);
//     }
    
//     return of({ success: true, timestamp: new Date() }).pipe(delay(1000));
//   }
// }