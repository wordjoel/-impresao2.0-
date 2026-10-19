import { describe, expect, it } from 'vitest';
import { dotsToMm, mmToDots, pxPerMm } from '../src/geometry/units';

describe('mmToDots', () => {
  it('203 dpi = 8 dots/mm (arredondado)', () => {
    expect(mmToDots(1, 203)).toBe(8);
    expect(mmToDots(25.4, 203)).toBe(203);
    // etiqueta padrão 50x30 mm
    expect(mmToDots(50, 203)).toBe(400);
    expect(mmToDots(30, 203)).toBe(240);
  });

  it('300 dpi', () => {
    expect(mmToDots(25.4, 300)).toBe(300);
    expect(mmToDots(50, 300)).toBe(591);
  });

  it('600 dpi', () => {
    expect(mmToDots(10, 600)).toBe(236);
  });

  it('zero e meio mm', () => {
    expect(mmToDots(0, 203)).toBe(0);
    expect(mmToDots(0.5, 203)).toBe(4);
  });
});

describe('dotsToMm', () => {
  it('é o inverso exato antes do arredondamento', () => {
    expect(dotsToMm(203, 203)).toBeCloseTo(25.4, 10);
    expect(dotsToMm(300, 300)).toBeCloseTo(25.4, 10);
  });

  it('ida e volta erra no máximo meio dot', () => {
    for (const dpi of [203, 300, 600]) {
      for (let mm = 0; mm <= 100; mm += 0.37) {
        const back = dotsToMm(mmToDots(mm, dpi), dpi);
        expect(Math.abs(back - mm)).toBeLessThanOrEqual(dotsToMm(0.5, dpi) + 1e-9);
      }
    }
  });
});

describe('pxPerMm', () => {
  it('zoom 1 em tela 96 dpi ≈ 3.78 px/mm', () => {
    expect(pxPerMm(1)).toBeCloseTo(3.7795, 4);
    expect(pxPerMm(2)).toBeCloseTo(7.559, 3);
  });
});
