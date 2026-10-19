import { describe, expect, it } from 'vitest';
import { afmMeasurer } from '../src/layout/metrics';
import { ellipsize, fitText, wrapText } from '../src/layout/text-layout';

const font = (sizeMm: number, bold = false) => ({ family: 'Helvetica', sizeMm, bold });
const w = (t: string, s: number) => afmMeasurer.width(t, font(s));

describe('métricas AFM', () => {
  it('soma das larguras Helvetica (H 722 + e 556 + l 222 + l 222 + o 556 = 2278)', () => {
    expect(w('Hello', 10)).toBeCloseTo(22.78, 10);
    expect(afmMeasurer.width('W', font(10, true))).toBeCloseTo(9.44, 10);
  });

  it('acentuado mede como a letra base', () => {
    expect(w('ç', 10)).toBe(w('c', 10));
    expect(w('Ã', 10)).toBe(w('A', 10));
  });
});

describe('quebra de linha', () => {
  it('quebra em espaços sem passar da largura e sem perder palavras', () => {
    const text = 'Teclado Mecânico RGB 87 Teclas';
    const lines = wrapText(text, 20, font(3), afmMeasurer);
    expect(lines.length).toBeGreaterThan(1);
    expect(lines.every((l) => w(l, 3) <= 20)).toBe(true);
    expect(lines.join(' ')).toBe(text);
  });

  it('palavra maior que a caixa é cortada por caractere', () => {
    const lines = wrapText('ABCDEFGHIJKLMNOPQRSTUVWXYZ', 10, font(3), afmMeasurer);
    expect(lines.length).toBeGreaterThan(1);
    expect(lines.every((l) => w(l, 3) <= 10)).toBe(true);
    expect(lines.join('')).toBe('ABCDEFGHIJKLMNOPQRSTUVWXYZ');
  });

  it('respeita \\n explícito e reticências cabem na largura', () => {
    expect(wrapText('A\nB', 50, font(3), afmMeasurer)).toEqual(['A', 'B']);
    const e = ellipsize('Teclado Mecânico RGB', 10, font(3), afmMeasurer);
    expect(e.endsWith('…')).toBe(true);
    expect(w(e, 3)).toBeLessThanOrEqual(10);
  });
});

describe('auto-fit', () => {
  const base = { fit: 'WRAP_SHRINK' as const, minSizeMm: 1.5 };

  it('texto curto fica no tamanho original', () => {
    const r = fitText({ ...base, text: 'Oi', widthMm: 30, heightMm: 6, font: font(3) }, afmMeasurer);
    expect(r).toMatchObject({ sizeMm: 3, lines: ['Oi'], overflow: false });
  });

  it('"Teclado Mecânico RGB 87 Teclas" em 30x6 mm: reduz e quebra até caber', () => {
    const r = fitText({ ...base, text: 'Teclado Mecânico RGB 87 Teclas', widthMm: 30, heightMm: 6, font: font(4) }, afmMeasurer);
    expect(r.overflow).toBe(false);
    expect(r.sizeMm).toBeLessThan(4);
    expect(r.sizeMm).toBeGreaterThanOrEqual(1.5);
    expect(r.lines.length * r.lineHeightMm).toBeLessThanOrEqual(6 + 1e-9);
    expect(r.lines.every((l) => w(l, r.sizeMm) <= 30)).toBe(true);
    // Maior tamanho que cabe: 0,1 mm acima já não cabe.
    const bigger = fitText({ ...base, fit: 'WRAP', text: 'Teclado Mecânico RGB 87 Teclas', widthMm: 30, heightMm: 6, font: font(r.sizeMm + 0.1) }, afmMeasurer);
    expect(bigger.overflow).toBe(true);
  });

  it('SHRINK mantém uma linha só', () => {
    const r = fitText({ ...base, fit: 'SHRINK', text: 'Teclado Mecânico RGB 87 Teclas', widthMm: 30, heightMm: 10, font: font(5) }, afmMeasurer);
    expect(r.lines).toHaveLength(1);
    expect(w(r.lines[0]!, r.sizeMm)).toBeLessThanOrEqual(30);
  });

  it('nem no mínimo cabe: corta, põe reticências e avisa', () => {
    const r = fitText({ ...base, text: 'Teclado Mecânico RGB 87 Teclas', widthMm: 5, heightMm: 2, font: font(3) }, afmMeasurer);
    expect(r.overflow).toBe(true);
    expect(r.lines[r.lines.length - 1]!.endsWith('…')).toBe(true);
  });
});
