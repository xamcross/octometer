import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PauseRefreshButton } from './pause-refresh-button';

describe('PauseRefreshButton', () => {
  let fixture: ComponentFixture<PauseRefreshButton>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PauseRefreshButton],
    }).compileComponents();

    fixture = TestBed.createComponent(PauseRefreshButton);
    fixture.detectChanges();
  });

  function button(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button');
  }

  it('shows the text "Pause refresh"', () => {
    expect(button().textContent).toContain('Pause refresh');
  });

  it('starts with aria-pressed set to false', () => {
    expect(button().getAttribute('aria-pressed')).toBe('false');
  });

  it('sets aria-pressed to true on the first click', () => {
    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('true');
  });

  it('sets aria-pressed back to false on the second click', () => {
    button().click();
    fixture.detectChanges();
    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('false');
  });

  it('starts with the pressed model set to the given initial value', () => {
    const other = TestBed.createComponent(PauseRefreshButton);
    other.componentRef.setInput('pressed', true);
    other.detectChanges();
    const otherButton: HTMLButtonElement = other.nativeElement.querySelector('button');
    expect(otherButton.getAttribute('aria-pressed')).toBe('true');
  });

  it('is a native button element with type="button" and no role override', () => {
    expect(button().tagName).toBe('BUTTON');
    expect(button().type).toBe('button');
    expect(button().getAttribute('role')).toBeNull();
  });

  it('keeps the text "Pause refresh" in both states', () => {
    expect(button().textContent?.trim()).toBe('Pause refresh');
    expect(button().getAttribute('aria-label')).toBeNull();
    expect(button().getAttribute('aria-labelledby')).toBeNull();

    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('true');
    expect(button().textContent?.trim()).toBe('Pause refresh');
  });

  it('writes aria-pressed as the text "false" or "true", never an absent attribute', () => {
    expect(button().hasAttribute('aria-pressed')).toBe(true);
    expect(button().getAttribute('aria-pressed')).toBe('false');

    button().click();
    fixture.detectChanges();
    expect(button().hasAttribute('aria-pressed')).toBe(true);
    expect(button().getAttribute('aria-pressed')).toBe('true');
  });
});
