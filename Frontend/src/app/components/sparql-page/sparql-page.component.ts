import { Component } from '@angular/core';
import { trigger, transition, style, animate } from '@angular/animations';
import { SparqlComponent } from '../sparql/sparql.component';

@Component({
  selector: 'app-sparql-page',
  imports: [SparqlComponent],
  template: `
    <div class="h-full flex items-start justify-center overflow-y-auto bg-gray-50" [@slideIn]>
      <div class="w-full max-w-5xl">
        <app-sparql></app-sparql>
      </div>
    </div>
  `,
  animations: [
    trigger('slideIn', [
      transition(':enter', [
        style({ transform: 'translateX(100%)', opacity: 0 }),
        animate('300ms ease-out', style({ transform: 'translateX(0)', opacity: 1 }))
      ]),
      transition(':leave', [
        animate('200ms ease-in', style({ transform: 'translateX(100%)', opacity: 0 }))
      ])
    ])
  ]
})
export class SparqlPageComponent {}
