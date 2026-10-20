import { describe, expect, it } from 'vitest';
import {
  addObject,
  createTemplate,
  createTextObject,
  newSequenceCounter,
  reserveSequence,
  resolveLabel,
  usesSequence,
} from '../src';

/** Passo 4 (2026-10-19): numeração contínua entre lotes. */

const dia = (iso: string) => new Date(`${iso}T10:00:00`);

describe('reserva de faixa', () => {
  it('faixas seguidas sem sobreposição', () => {
    let c = newSequenceCounter('NEVER', dia('2026-10-19'));
    const a = reserveSequence(c, 50, dia('2026-10-19'));
    c = a.counter;
    const b = reserveSequence(c, 30, dia('2026-10-20'));
    expect([a.start, b.start, b.counter.next]).toEqual([0, 50, 80]);
  });

  it('DAILY: recomeça no primeiro lote de um dia novo, não no mesmo dia', () => {
    const c0 = newSequenceCounter('DAILY', dia('2026-10-19'));
    const a = reserveSequence(c0, 10, dia('2026-10-19'));
    const b = reserveSequence(a.counter, 5, dia('2026-10-19'));
    const c = reserveSequence(b.counter, 7, dia('2026-10-20'));
    expect([a.start, b.start, c.start, c.counter.next, c.counter.day]).toEqual([0, 10, 0, 7, '2026-10-20']);
  });

  it('recusa reserva vazia ou fracionada', () => {
    expect(() => reserveSequence(newSequenceCounter(), 0)).toThrow(RangeError);
    expect(() => reserveSequence(newSequenceCounter(), 1.5)).toThrow(RangeError);
  });
});

describe('SEQ com a faixa do job', () => {
  const t = addObject(
    createTemplate(50, 20),
    createTextObject({ id: 'n', content: 'Nº {{SEQ(1, 1, 6)}} — linha {{$row}}', x: 1, y: 1, width: 48, height: 6, fit: 'NONE' }),
  );
  const text = (index: number, seqOffset?: number) => {
    const el = resolveLabel(t, {}, { index, seqOffset }).elements[0]!;
    return el.kind === 'text' ? el.text : '';
  };

  it('sem faixa: igual a antes (posição no lote)', () => {
    expect(text(0)).toBe('Nº 000001 — linha 1');
  });

  it('com faixa: só o SEQ anda; $row continua a posição dentro do lote', () => {
    expect(text(0, 100)).toBe('Nº 000101 — linha 1');
    expect(text(2, 100)).toBe('Nº 000103 — linha 3');
  });

  it('usesSequence: só templates com SEQ visível consomem numeração', () => {
    expect(usesSequence(t)).toBe(true);
    expect(usesSequence(createTemplate())).toBe(false);
    const hidden = addObject(createTemplate(), createTextObject({ content: '{{SEQ(1)}}', visible: false }));
    expect(usesSequence(hidden)).toBe(false);
  });
});
