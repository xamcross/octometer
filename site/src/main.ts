import {
  WAITLIST_SUCCESS_MESSAGE,
  WAITLIST_ADDRESS_ERROR_MESSAGE,
  WAITLIST_SERVER_ERROR_MESSAGE,
} from './shared/waitlist-messages';

// The focus move into the waitlist email field (issue #288). The anchor
// navigation moves the scroll on its own ("html { scroll-behavior: smooth }",
// site.css); this script moves only the focus, with "preventScroll", so the
// page does not scroll two times.
function moveFocusToWaitlistField(): void {
  const button = document.querySelector<HTMLAnchorElement>('a[href="#waitlist-email"]');
  button?.addEventListener('click', () => {
    const field = document.getElementById('waitlist-email');
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

// The waitlist form (issue #291). The form posts "application/x-www-form-
// urlencoded" with no script, to "/api/waitlist"; this function adds the
// fetch submit so the state shows in place, with no full page reload, on
// a browser that runs this script.
function setWaitlistStatus(region: HTMLElement, tone: 'ok' | 'bad' | null, text: string): void {
  region.textContent = text;
  region.classList.remove('wl-status-ok', 'wl-status-bad');
  if (tone) {
    region.classList.add(`wl-status-${tone}`);
  }
}

// The no-JavaScript path redirects to "/?joined=1#waitlist" and the
// like (the maintainer's notes); this function reads the query on load,
// so a full page reload still shows the one answer.
function showWaitlistStateFromQuery(): void {
  const region = document.getElementById('waitlist-status');
  if (!region) {
    return;
  }
  const params = new URLSearchParams(window.location.search);
  if (params.get('joined') === '1') {
    setWaitlistStatus(region, 'ok', WAITLIST_SUCCESS_MESSAGE);
  } else if (params.get('error') === 'address') {
    setWaitlistStatus(region, 'bad', WAITLIST_ADDRESS_ERROR_MESSAGE);
  } else if (params.get('error') === 'server') {
    setWaitlistStatus(region, 'bad', WAITLIST_SERVER_ERROR_MESSAGE);
  }
}

function interceptWaitlistSubmit(): void {
  const form = document.querySelector<HTMLFormElement>('form.waitlist-form');
  const region = document.getElementById('waitlist-status');
  if (!form || !region) {
    return;
  }
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const data = new FormData(form);
    const email = String(data.get('email') ?? '');
    const website = String(data.get('website') ?? '');
    fetch(form.action, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, website }),
    })
      .then(async (response) => {
        const body = (await response.json().catch(() => null)) as { message?: string } | null;
        if (response.ok) {
          setWaitlistStatus(region, 'ok', body?.message ?? WAITLIST_SUCCESS_MESSAGE);
          form.reset();
        } else if (response.status === 400) {
          setWaitlistStatus(region, 'bad', body?.message ?? WAITLIST_ADDRESS_ERROR_MESSAGE);
        } else {
          setWaitlistStatus(region, 'bad', body?.message ?? WAITLIST_SERVER_ERROR_MESSAGE);
        }
      })
      .catch(() => {
        setWaitlistStatus(region, 'bad', WAITLIST_SERVER_ERROR_MESSAGE);
      });
  });
}

moveFocusToWaitlistField();
revealFlowNodesOnScroll();
showWaitlistStateFromQuery();
interceptWaitlistSubmit();
