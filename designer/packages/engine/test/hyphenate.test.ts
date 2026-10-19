import { describe, expect, it } from 'vitest';
import { hyphenationPoints } from '../src/layout/hyphenate-pt';
import { afmMeasurer } from '../src/layout/metrics';
import { fitText, wrapText } from '../src/layout/text-layout';
import { addObject, createTemplate, createTextObject, resolveLabel } from '../src';

/** Mostra os pontos como "pro-mo-ção" para o teste ler como um dicionário. */
const split = (w: string) => {
  const pts = hyphenationPoints(w);
  let out = '';
  let last = 0;
  for (const p of pts) {
    out += `${w.slice(last, p)}-`;
    last = p;
  }
  return out + w.slice(last);
};

const font = (sizeMm: number) => ({ family: 'Helvetica', sizeMm, bold: false });
const w = (t: string, s: number) => afmMeasurer.width(t, font(s));

describe('sílabas do português (pontos seguros)', () => {
  it.each([
    ['promoção', 'pro-mo-ção'],
    ['Mecânico', 'Me-câ-ni-co'],
    ['teclado', 'te-cla-do'],
    ['carro', 'car-ro'],
    ['massa', 'mas-sa'],
    ['nascer', 'nas-cer'],
    ['exceção', 'ex-ce-ção'],
    ['molhado', 'mo-lha-do'],
    ['chocolate', 'cho-co-la-te'],
    ['transporte', 'trans-por-te'],
    ['abstrato', 'abs-tra-to'],
    ['admitir', 'ad-mi-tir'],
    ['guerra', 'guer-ra'],
    ['aquilo', 'aqui-lo'],
    ['AÇÚCAR', 'AÇÚ-CAR'],
  ])('%s → %s', (word, expected) => {
    expect(split(word)).toBe(expected);
  });

  it('nunca separa vogais seguidas (ditongo × hiato)', () => {
    expect(hyphenationPoints('saia')).toEqual([]);
    expect(split('cooperativa')).toBe('coo-pe-ra-ti-va');
  });

  it('deixa pelo menos 2 letras de cada lado', () => {
    expect(split('olho')).toBe('olho');
    expect(split('ave')).toBe('ave');
    expect(split('casa')).toBe('ca-sa');
  });

  it('código, SKU e URL não são hifenizados; pontuação final fica com a palavra', () => {
    expect(hyphenationPoints('ATHOS-123456')).toEqual([]);
    expect(hyphenationPoints('SKU123')).toEqual([]);
    expect(hyphenationPoints('https://x.com')).toEqual([]);
    expect(split('promoção!')).toBe('pro-mo-ção!');
  });
});

describe('quebra de linha com hifenização', () => {
  const text = 'OFERTA PROMOÇÃO IMPERDÍVEL';

  it('desligada: resultado idêntico ao comportamento anterior', () => {
    expect(wrapText(text, 22, font(4), afmMeasurer, false)).toEqual(wrapText(text, 22, font(4), afmMeasurer));
  });

  it('ligada: encaixa sílabas com hífen, sem passar da largura e sem perder letras', () => {
    const lines = wrapText(text, 22, font(4), afmMeasurer, true);
    expect(lines.some((l) => l.endsWith('-'))).toBe(true);
    expect(lines.every((l) => w(l, 4) <= 22)).toBe(true);
    expect(lines.join(' ').replace(/- /g, '')).toBe(text);
  });

  it('palavra sem ponto seguro continua cortada por caractere, como antes', () => {
    const sku = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123';
    expect(wrapText(sku, 10, font(3), afmMeasurer, true)).toEqual(wrapText(sku, 10, font(3), afmMeasurer, false));
  });

  it('auto-fit com hifenização nunca escolhe fonte menor que sem ela', () => {
    const base = { text, widthMm: 22, heightMm: 12, font: font(5), fit: 'WRAP_SHRINK' as const, minSizeMm: 1.5 };
    const off = fitText(base, afmMeasurer);
    const on = fitText({ ...base, hyphenate: true }, afmMeasurer);
    expect(on.sizeMm).toBeGreaterThanOrEqual(off.sizeMm);
    expect(on.overflow).toBe(false);
  });

  it('template antigo (sem o campo) resolve igual a hyphenate=false', () => {
    const obj = createTextObject({ id: 't', content: text, x: 1, y: 1, width: 22, height: 12 });
    const legacy = { ...obj } as Record<string, unknown>;
    delete legacy.hyphenate;
    const a = resolveLabel(addObject(createTemplate(30, 20), legacy as never), {});
    const b = resolveLabel(addObject(createTemplate(30, 20), { ...obj, hyphenate: false }), {});
    expect(JSON.stringify(a.elements)).toBe(JSON.stringify(b.elements));
  });
});
