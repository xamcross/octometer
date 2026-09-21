import { TestBed } from '@angular/core/testing';

import { Announcer } from './announcer';

describe('Announcer', () => {
  let announcer: Announcer;

  beforeEach(() => {
    announcer = TestBed.inject(Announcer);
  });

  it('starts with an empty message', () => {
    expect(announcer.message()).toBe('');
  });

  it('sets the message on an announce call', () => {
    announcer.announce('The refresh is paused.');
    expect(announcer.message()).toBe('The refresh is paused.');
  });
});
