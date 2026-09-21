import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Announcer } from './announcer';

describe('Announcer', () => {
  let announcer: Announcer;

  beforeEach(() => {
    announcer = TestBed.inject(Announcer);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts with an empty message', () => {
    expect(announcer.message()).toBe('');
  });

  it('clears the message first, then sets it on the next turn', async () => {
    announcer.announce('The refresh is paused.');
    expect(announcer.message()).toBe('');

    await vi.advanceTimersByTimeAsync(100);
    expect(announcer.message()).toBe('The refresh is paused.');
  });

  it('writes the message again when the text repeats', async () => {
    announcer.announce('The refresh is paused.');
    await vi.advanceTimersByTimeAsync(100);
    expect(announcer.message()).toBe('The refresh is paused.');

    announcer.announce('The refresh is paused.');
    expect(announcer.message()).toBe('');

    await vi.advanceTimersByTimeAsync(100);
    expect(announcer.message()).toBe('The refresh is paused.');
  });

  it('clears the message on a clear call', async () => {
    announcer.announce('The refresh is paused.');
    await vi.advanceTimersByTimeAsync(100);

    announcer.clear();
    expect(announcer.message()).toBe('');
  });
});
