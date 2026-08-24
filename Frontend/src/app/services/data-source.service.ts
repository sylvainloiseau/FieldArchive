// // src/app/services/data-source.service.ts

// import { Injectable } from '@angular/core';
// import { HttpClient, HttpHeaders } from '@angular/common/http';
// import { Observable, BehaviorSubject } from 'rxjs';
// import { tap } from 'rxjs/operators';
// import { DataSource, CreateInternalDataSourceRequest, CreateExternalDataSourceRequest } from '../models/data-source.model';

// @Injectable({
//   providedIn: 'root'
// })
// export class DataSourceService {
//   private apiUrl = 'http://localhost:8080/api'; // Update with your backend URL
//   private dataSourcesSubject = new BehaviorSubject<DataSource[]>([]);
//   public dataSources$ = this.dataSourcesSubject.asObservable();

//   private httpOptions = {
//     headers: new HttpHeaders({
//       'Content-Type': 'application/json'
//     })
//   };

//   constructor(private http: HttpClient) {}

//   // Get all data sources
//   getAllDataSources(): Observable<DataSource[]> {
//     return this.http.get<DataSource[]>(`${this.apiUrl}/datasources`)
//       .pipe(
//         tap(sources => this.dataSourcesSubject.next(sources))
//       );
//   }

//   // Get a single data source by ID
//   getDataSourceById(id: string): Observable<DataSource> {
//     return this.http.get<DataSource>(`${this.apiUrl}/datasources/${id}`);
//   }

//   // Create a new data source
//   createDataSource(dataSource: CreateDataSourceRequest): Observable<DataSource> {
//     return this.http.post<DataSource>(
//       `${this.apiUrl}/datasources`,
//       dataSource,
//       this.httpOptions
//     ).pipe(
//       tap(() => this.getAllDataSources().subscribe()) // Refresh list
//     );
//   }

//   // Update an existing data source
//   updateDataSource(id: string, dataSource: Partial<DataSource>): Observable<DataSource> {
//     return this.http.put<DataSource>(
//       `${this.apiUrl}/datasources/${id}`,
//       dataSource,
//       this.httpOptions
//     ).pipe(
//       tap(() => this.getAllDataSources().subscribe()) // Refresh list
//     );
//   }

//   // Delete a data source
//   deleteDataSource(id: string): Observable<void> {
//     return this.http.delete<void>(`${this.apiUrl}/datasources/${id}`)
//       .pipe(
//         tap(() => this.getAllDataSources().subscribe()) // Refresh list
//       );
//   }

//   // Sync external data source
//   syncExternalSource(id: string): Observable<any> {
//     return this.http.post(`${this.apiUrl}/datasources/${id}/sync`, {});
//   }
// }