import { Component } from '@angular/core';

import { RefreshBar } from '../refresh-bar/refresh-bar';

/**
 * Placeholder view for the /apps/:appId/users route.
 * A later issue adds the real user table (D28, level 2), and keeps the
 * refresh bar between the heading and the table (#92).
 */
@Component({
  selector: 'app-users',
  imports: [RefreshBar],
  templateUrl: './users.html',
})
export class Users {}
