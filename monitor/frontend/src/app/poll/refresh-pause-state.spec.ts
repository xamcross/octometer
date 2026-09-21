import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { RefreshPauseState } from './refresh-pause-state';

describe('RefreshPauseState', () => {
  it('starts with paused set to false', () => {
    const state = TestBed.inject(RefreshPauseState);
    expect(state.paused()).toBe(false);
  });

  it('gives the same signal to each caller, so one root instance holds the state', () => {
    const first = TestBed.inject(RefreshPauseState);
    const second = TestBed.inject(RefreshPauseState);

    first.paused.set(true);

    expect(second.paused()).toBe(true);
  });
});
