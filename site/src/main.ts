// The focus move into the waitlist email field (issue #288). The anchor
// navigation moves the scroll on its own ("html { scroll-behavior: smooth }",
// site.css); this script moves only the focus, with "preventScroll", so the
// page does not scroll two times.
//
// The scroll reveal of the flow diagram comes with issue #289. This file
// grows with that script then.
const button = document.querySelector<HTMLAnchorElement>('a[href="#waitlist-email"]');

button?.addEventListener('click', () => {
  const field = document.getElementById('waitlist-email');
  // Issue #291 adds the real field. Until then, the anchor jump alone
  // moves the page, with no focus move.
  if (field) {
    field.focus({ preventScroll: true });
  }
});
