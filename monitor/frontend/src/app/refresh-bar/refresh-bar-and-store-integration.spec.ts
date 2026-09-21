import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { PollStore, createPollStore } from '../poll/poll-store';
import { RefreshBar } from './refresh-bar';

/**
 * A host component. It holds one bar and one poll store, the way a real
 * table view of #20, #52, or #53 will hold the two together.
 */
@Component({
  selector: 'app-refresh-bar-and-store-test-host',
  imports: [RefreshBar],
  template: `<app-refresh-bar />`,
})
class RefreshBarAndStoreTestHost {
  calls = 0;
  store: PollStore<string> = createPollStore(() => {
    this.calls++;
    return of(`v${this.calls}`);
  });
}

describe('RefreshBar next to a poll store, in one view', () => {
  let fixture: ComponentFixture<RefreshBarAndStoreTestHost>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(RefreshBarAndStoreTestHost);
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.useRealTimers();
    httpMock.verify();
  });

  it('sends one health request for the view, and no second request in 30 seconds', () => {
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });
    vi.advanceTimersByTime(0);

    expect(fixture.componentInstance.calls).toBe(1);

    vi.advanceTimersByTime(30_000);
    httpMock.expectNone('/api/health');
    expect(fixture.componentInstance.calls).toBe(4);
  });
});
