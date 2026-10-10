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

  function icon(): HTMLElement {
    return fixture.nativeElement.querySelector('.icon');
  }

  it('shows the text "Pause refresh" while the refresh runs', () => {
    expect(button().textContent).toContain('Pause refresh');
  });

  it('shows a pause icon, marked aria-hidden, while the refresh runs', () => {
    expect(icon().textContent?.trim()).toBe('⏸');
    expect(icon().getAttribute('aria-hidden')).toBe('true');
  });

  it('starts with aria-pressed set to false', () => {
    expect(button().getAttribute('aria-pressed')).toBe('false');
  });

  it('shows the text "Resume refresh" and a play icon on the first click', () => {
    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('true');
    expect(button().textContent).toContain('Resume refresh');
    expect(icon().textContent?.trim()).toBe('▶');
    expect(icon().getAttribute('aria-hidden')).toBe('true');
  });

  it('sets aria-pressed back to false and the text back to "Pause refresh" on the second click', () => {
    button().click();
    fixture.detectChanges();
    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('false');
    expect(button().textContent).toContain('Pause refresh');
  });

  it('starts with the pressed model set to the given initial value', () => {
    const other = TestBed.createComponent(PauseRefreshButton);
    other.componentRef.setInput('pressed', true);
    other.detectChanges();
    const otherButton: HTMLButtonElement = other.nativeElement.querySelector('button');
    expect(otherButton.getAttribute('aria-pressed')).toBe('true');
    expect(otherButton.textContent).toContain('Resume refresh');
  });

  it('is a native button element with type="button" and no role override', () => {
    expect(button().tagName).toBe('BUTTON');
    expect(button().type).toBe('button');
    expect(button().getAttribute('role')).toBeNull();
  });

  it('keeps the icon aria-hidden and gives the button no extra label, in both states', () => {
    expect(button().getAttribute('aria-label')).toBeNull();
    expect(button().getAttribute('aria-labelledby')).toBeNull();
    expect(icon().getAttribute('aria-hidden')).toBe('true');

    button().click();
    fixture.detectChanges();
    expect(button().getAttribute('aria-pressed')).toBe('true');
    expect(icon().getAttribute('aria-hidden')).toBe('true');
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
