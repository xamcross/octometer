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

  it('stops the requests when the button is pressed', () => {
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });
    vi.advanceTimersByTime(0);
    expect(fixture.componentInstance.calls).toBe(1);

    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('true');

    vi.advanceTimersByTime(10_000);
    expect(fixture.componentInstance.calls).toBe(1);
  });

  it('resumes the requests when the button is pressed again', () => {
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });
    vi.advanceTimersByTime(0);

    button().click();
    fixture.detectChanges();
    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('false');

    vi.advanceTimersByTime(10_000);
    expect(fixture.componentInstance.calls).toBe(2);
  });
});
