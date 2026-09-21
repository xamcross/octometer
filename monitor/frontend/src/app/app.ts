import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Root shell of the app. It holds the router outlet only.
 */
@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {}
