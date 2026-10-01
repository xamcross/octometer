// The focus move into the waitlist email field (issue #288). The anchor
// navigation moves the scroll on its own ("html { scroll-behavior: smooth }",
// site.css); this script moves only the focus, with "preventScroll", so the
// page does not scroll two times.
function moveFocusToWaitlistField(): void {
  const button = document.querySelector<HTMLAnchorElement>('a[href="#waitlist-email"]');
  button?.addEventListener('click', () => {
    const field = document.getElementById('waitlist-email');
    // Issue #291 adds the real field. Until then, the anchor jump alone
    // moves the page, with no focus move.
    if (field) {
      field.focus({ preventScroll: true });
    }
  });
}

// The scroll reveal of the flow illustration (issue #289): each ".node"
// gets the class "node-in" when it scrolls into view. The inline script of
// "index.html" adds the class "js" to the root element behind the same
// guard, before this module loads, so the illustration never flashes
// unrevealed on a browser with no "IntersectionObserver".
function revealFlowNodesOnScroll(): void {
  if (typeof window.IntersectionObserver !== 'function') {
    return;
  }
  const nodes = document.querySelectorAll('.node');
  if (!nodes.length) {
    return;
  }
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) {
          continue;
        }
        entry.target.classList.add('node-in');
        observer.unobserve(entry.target);
      }
    },
    { rootMargin: '0px 0px -8% 0px' },
  );
  nodes.forEach((node) => observer.observe(node));
}

moveFocusToWaitlistField();
revealFlowNodesOnScroll();
