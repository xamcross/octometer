import { Component, input } from '@angular/core';

/**
 * The mascot mark of the cartoon look (D45): a round head with two eyes,
 * a smile, and eight short arms. The mark carries no meaning of its own,
 * so it stays out of the accessibility tree.
 *
 * The `size` input sets the width and the height, in CSS px, of the
 * `viewBox="0 0 32 32"` mark.
 */
@Component({
  selector: 'app-octo-mark',
  templateUrl: './octo-mark.html',
  styleUrl: './octo-mark.css',
})
export class OctoMark {
  /** The width and the height of the mark, in CSS px. */
  size = input(28);
}
