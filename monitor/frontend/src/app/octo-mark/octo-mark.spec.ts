import { ComponentFixture, TestBed } from '@angular/core/testing';

import { OctoMark } from './octo-mark';

describe('OctoMark', () => {
  let fixture: ComponentFixture<OctoMark>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OctoMark],
    }).compileComponents();

    fixture = TestBed.createComponent(OctoMark);
  });

  function svg(): SVGElement {
    return fixture.nativeElement.querySelector('svg');
  }

  it('renders the mark at the default size of 28 CSS px', () => {
    fixture.detectChanges();

    expect(svg().getAttribute('width')).toBe('28');
    expect(svg().getAttribute('height')).toBe('28');
  });

  it('renders the mark at a given size of 96 CSS px', () => {
    fixture.componentRef.setInput('size', 96);
    fixture.detectChanges();

    expect(svg().getAttribute('width')).toBe('96');
    expect(svg().getAttribute('height')).toBe('96');
  });

  it('keeps the viewBox at 0 0 32 32, apart from the given size', () => {
    fixture.componentRef.setInput('size', 96);
    fixture.detectChanges();

    expect(svg().getAttribute('viewBox')).toBe('0 0 32 32');
  });

  it('hides the mark from a screen reader, because it carries no meaning of its own', () => {
    fixture.detectChanges();

    expect(svg().getAttribute('aria-hidden')).toBe('true');
    expect(svg().getAttribute('focusable')).toBe('false');
  });
});
