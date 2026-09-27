import { Component } from '@angular/core';

import { EmptyState } from '../empty-state/empty-state';

/** View for an unknown URL. It shows a link back to the apps view. */
@Component({
  selector: 'app-not-found',
  imports: [EmptyState],
  templateUrl: './not-found.html',
  styleUrl: './not-found.css',
})
export class NotFound {}
