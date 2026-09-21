import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Announcer } from '../announcer';
import { RefreshPauseState } from '../poll/refresh-pause-state';
import { RefreshBar } from './refresh-bar';

describe('RefreshBar', () => {
  let fixture: ComponentFixture<RefreshBar>;
  let httpMock: HttpTestingController;
  let announcer: Announcer;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RefreshBar],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    announcer = TestBed.inject(Announcer);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(RefreshBar);
    fixture.detectChanges();
  });

  afterEach(() => {
    // Answers each health request that a test left open, so the request
    // does not stay open into a later test. The time advance lets a
    // not-yet-fired timer(0) send its request first.
    vi.advanceTimersByTime(0);
    for (const request of httpMock.match('/api/health')) {
      request.flush({ refreshSeconds: 10 });
    }
    vi.useRealTimers();
    httpMock.verify();
  });

  function button(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button');
  }

  function text(): string {
    return fixture.nativeElement.querySelector('p').textContent.trim();
  }

  function answerHealth(refreshSeconds: number): void {
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds });
    vi.advanceTimersByTime(0);
    fixture.detectChanges();
  }

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows the button with the accessible name "Pause refresh"', () => {
    expect(button().textContent?.trim()).toBe('Pause refresh');
    expect(button().getAttribute('aria-pressed')).toBe('false');
  });

  it('shows a text before the health answer arrives', () => {
    expect(text()).toBe('The app reads the refresh interval.');
  });

  it('shows the interval text once the health answer arrives', () => {
    answerHealth(10);
    expect(text()).toBe('The table refreshes each 10 seconds.');
  });

  it('shows the paused text once the user presses the button, before the health answer', () => {
    button().click();
    fixture.detectChanges();
    expect(text()).toBe('The refresh is paused.');
  });

  it('shows the paused text instead of the interval text once the user presses the button', () => {
    answerHealth(10);
    button().click();
    fixture.detectChanges();
    expect(text()).toBe('The refresh is paused.');
  });

  it('sets aria-pressed to true, and keeps the accessible name, on a press', () => {
    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('true');
    expect(button().textContent?.trim()).toBe('Pause refresh');
  });

  it('writes "Refresh paused" to the announcer on a press', async () => {
    button().click();
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(100);
    expect(announcer.message()).toBe('Refresh paused');
  });

  it('writes "Refresh resumed" to the announcer on the second press', async () => {
    button().click();
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(100);

    button().click();
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(100);
    expect(announcer.message()).toBe('Refresh resumed');
  });

  it('adds no role="status" region of its own', () => {
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
  });

  it('shares the paused state with a poll store through RefreshPauseState', () => {
    button().click();
    fixture.detectChanges();

    const pauseState = TestBed.inject(RefreshPauseState);
    expect(pauseState.paused()).toBe(true);
  });

  it('reads an already-paused state from a previous view, and shows the paused text at once', () => {
    TestBed.inject(RefreshPauseState).paused.set(true);

    const otherFixture = TestBed.createComponent(RefreshBar);
    otherFixture.detectChanges();
    const otherText = otherFixture.nativeElement.querySelector('p').textContent.trim();

    expect(otherText).toBe('The refresh is paused.');
  });
});
