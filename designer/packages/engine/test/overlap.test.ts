import { describe, expect, it } from 'vitest';
import { addObject, checkGeometry, createBarcode1D, createBarcode2D, createBox, createTemplate, createTextObject, type LabelObject } from '../src';

/** 2026-10-19 — alerta de objeto invadindo a área de um código de barras (BARCODE_OVERLAP). */

const ean = createBarcode1D({ id: 'ean', name: 'EAN', symbology: 'EAN13', content: '4006381333931', x: 10, y: 10, width: 40, height: 15 });

function overlaps(...objs: LabelObject[]) {
  let t = createTemplate(80, 50);
  for (const o of objs) t = addObject(t, o);
  return checkGeometry(t).filter((i) => i.code === 'BARCODE_OVERLAP');
}

describe('avisa', () => {
  it('texto sobre o código, em qualquer ordem de camada', () => {
    const txt = createTextObject({ id: 't', name: 'Preço', content: 'R$ 9,90', x: 20, y: 15, width: 20, height: 5 });
    const above = overlaps(ean, txt);
    const below = overlaps(txt, ean);
    expect(above).toHaveLength(1);
    expect(below).toHaveLength(1);
    expect(above[0]!.objectId).toBe('ean');
    expect(above[0]!.message).toContain('Preço');
  });

  it('dois códigos encostados um no outro', () => {
    const qr = createBarcode2D({ id: 'qr', name: 'QR', content: 'X', x: 45, y: 12, width: 12, height: 12 });
    expect(overlaps(ean, qr)).toHaveLength(1);
  });

  it('caixa preenchida e moldura cuja borda cruza o código', () => {
    expect(overlaps(ean, createBox({ id: 'f', name: 'Faixa', x: 0, y: 12, width: 80, height: 4, filled: true }))).toHaveLength(1);
    expect(overlaps(ean, createBox({ id: 'm', name: 'Moldura', x: 30, y: 5, width: 40, height: 30, borderMm: 0.3 }))).toHaveLength(1);
  });

  it('objeto girado é medido pela caixa visível (girada)', () => {
    // 40 x 4 mm em (55,5) girado 90°: ocupa x 51..55, y 5..45 — cruza o EAN (x 10..50)? não; em (48,5) sim.
    const far = createTextObject({ id: 'g1', name: 'Girado', content: 'X', x: 55, y: 5, width: 40, height: 4, rotation: 90 });
    const near = createTextObject({ id: 'g2', name: 'Girado', content: 'X', x: 48, y: 5, width: 40, height: 4, rotation: 90 });
    expect(overlaps(ean, far)).toHaveLength(0);
    expect(overlaps(ean, near)).toHaveLength(1);
  });
});

describe('não avisa', () => {
  it('moldura vazia que só contorna o código', () => {
    expect(overlaps(ean, createBox({ id: 'm', name: 'Moldura', x: 5, y: 5, width: 50, height: 25, borderMm: 0.3 }))).toEqual([]);
  });

  it('texto sobre texto (sem código envolvido)', () => {
    const a = createTextObject({ id: 'a', content: 'A', x: 0, y: 0, width: 20, height: 5 });
    const b = createTextObject({ id: 'b', content: 'B', x: 5, y: 2, width: 20, height: 5 });
    expect(overlaps(a, b)).toEqual([]);
  });

  it('objeto oculto não conta', () => {
    expect(overlaps(ean, createTextObject({ id: 't', content: 'X', x: 20, y: 15, width: 20, height: 5, visible: false }))).toEqual([]);
  });

  it('só encostar na borda não é invadir', () => {
    expect(overlaps(ean, createTextObject({ id: 't', content: 'X', x: 50, y: 10, width: 10, height: 5 }))).toEqual([]);
  });
});
