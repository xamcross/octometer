import { Component } from '@angular/core';

import { RefreshBar } from '../refresh-bar/refresh-bar';

/**
 * Placeholder view for the /apps route.
 * A later issue adds the real app table (D28, level 1), and keeps the
 * refresh bar between the heading and the table (#92).
 */
@Component({
  selector: 'app-apps',
  imports: [RefreshBar],
  templateUrl: './apps.html',
})
export class Apps {}
