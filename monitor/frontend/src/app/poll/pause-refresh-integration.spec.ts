import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { PauseRefreshButton } from './pause-refresh-button';
import { PollStore, createPollStore } from './poll-store';

/**
 * A host component. It binds the button to a poll store, the way a view does.
 */
@Component({
  selector: 'app-pause-refresh-test-host',
  imports: [PauseRefreshButton],
  template: `<app-pause-refresh-button [(pressed)]="store.paused" />`,
})
class PauseRefreshTestHost {
  calls = 0;
  store: PollStore<string> = createPollStore(() => {
    this.calls++;
    return of(`v${this.calls}`);
  });
}

describe('PauseRefreshButton bound to a poll store', () => {
  let fixture: ComponentFixture<PauseRefreshTestHost>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(PauseRefreshTestHost);
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.useRealTimers();
    httpMock.verify();
  });

  function button(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button');
  }

  /** Answers the health request and runs the first data tick. */
  function startStore(refreshSeconds = 10): void {
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds });
    vi.advanceTimersByTime(0);
  }

  it('stops the requests when the button is pressed', () => {
    startStore(10);
    expect(fixture.componentInstance.calls).toBe(1);

    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('true');

    // A change from a click on a mounted button flushes through Angular's
    // own change detection, so a plain time advance is enough here.
    vi.advanceTimersByTime(0);
    vi.advanceTimersByTime(1);
    vi.advanceTimersByTime(10_000);
    expect(fixture.componentInstance.calls).toBe(1);
  });

  it('resumes the requests when the button is pressed again', () => {
    startStore(10);

    button().click();
    fixture.detectChanges();
    vi.advanceTimersByTime(0);
    vi.advanceTimersByTime(1);
    expect(button().getAttribute('aria-pressed')).toBe('true');

    // Some time passes while the store is paused, then the user unpauses it.
    vi.advanceTimersByTime(3_000);
    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('false');

    // The second request comes at once, well before the 10 s interval.
    vi.advanceTimersByTime(0);
    vi.advanceTimersByTime(1);
    expect(fixture.componentInstance.calls).toBe(2);
  });
});
