import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** View for an unknown URL. It shows a link back to the app list. */
@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  templateUrl: './not-found.html',
  styleUrl: './not-found.css',
})
export class NotFound {}
