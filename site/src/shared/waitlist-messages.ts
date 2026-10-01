// The three answer texts of the waitlist form (issue #291, the approved
// mockup and the brief review correction M3). The worker and the browser
// script import the same constants, so the two sides never drift apart.

export const WAITLIST_SUCCESS_MESSAGE =
  'Check your mail. Open the confirmation link, and your address joins the list.';

export const WAITLIST_ADDRESS_ERROR_MESSAGE =
  'We need a full address, such as you@example.com. Correct the address, then send it again.';

export const WAITLIST_SERVER_ERROR_MESSAGE =
  'We could not save your address. Try again in a minute.';
