import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { OctoMark } from '../octo-mark/octo-mark';

/**
 * The empty-state view (D45). It shows the mascot mark, one sentence,
 * and an optional link, in place of a plain paragraph. Each empty view
 * and the not-found page use it, so an empty screen gives the user one
 * clear next step.
 *
 * `text` is the sentence, or its first part when a link follows it.
 * `linkText` is the label of the link, and `linkPath` is its route. The
 * link renders inside the sentence (`docs/design/mockup.html` line
 * 383), for example "No app yet. Add one on <a>Manage apps</a>.". The
 * link renders only while `linkPath` holds a value. `textAfter` is the
 * words after the link, such as the closing full stop.
 *
 * Each input is plain text, never HTML: the template shows it through
 * text interpolation only.
 */
@Component({
  selector: 'app-empty-state',
  imports: [OctoMark, RouterLink],
  templateUrl: './empty-state.html',
  styleUrl: './empty-state.css',
})
export class EmptyState {
  /** The sentence, or its part before the link. */
  text = input.required<string>();

  /** The label of the link. Ignored while linkPath is empty. */
  linkText = input('');

  /** The route of the link. An empty string renders no link. */
  linkPath = input('');

  /** The words after the link, such as a closing full stop. */
  textAfter = input('');
}
