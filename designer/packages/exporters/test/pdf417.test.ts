import { describe, expect, it } from 'vitest';
import {
  addObject,
  createBarcode2D,
  createTemplate,
  encode2D,
  MonoBitmap,
  PDF417_ROW_MODULES,
  RASTER_2D,
  resolveLabel,
  symbolBox2D,
  type LabelTemplate,
} from '@athos-label/engine';
import { compileLabel, epl, labelToCommands, labelToSvg, raster2D, tspl, zpl } from '../src';

/** PDF417 (2026-10-19): retangular, linhas de 3 módulos, desenhado como imagem nas impressoras. */

const DATA = '(01)10012345678902(17)261231(10)LOTE1';

function one(content = DATA, width = 70, height = 25, rotation = 0): LabelTemplate {
  return addObject(createTemplate(80, 40), createBarcode2D({ id: 'p', symbology: 'PDF417', content, x: 2, y: 2, width, height, rotation }));
}

const resolved = (t: LabelTemplate, opts = {}) => {
  const el = resolveLabel(t, {}, { dpi: 203, ...opts }).elements[0]!;
  if (el.kind !== 'barcode2d') throw new Error(`esperado barcode2d, veio ${el.kind}`);
  return el;
};

describe('codificação', () => {
  it('matriz com uma entrada por linha (rows × cols) e mais larga que alta', () => {
    const enc = encode2D('PDF417', DATA);
    expect(enc.matrix.length).toBe(enc.rows * enc.cols);
    expect(enc.cols).toBeGreaterThan(enc.rows);
    expect(RASTER_2D.has('PDF417')).toBe(true);
    expect(RASTER_2D.has('QR')).toBe(false);
  });
});

describe('resolver', () => {
  it('módulo cabe na largura E na altura; caixa = cols × (rows × 3) módulos', () => {
    const el = resolved(one());
    expect(el.rowModules).toBe(PDF417_ROW_MODULES);
    const box = symbolBox2D(el);
    expect(box.widthMm).toBeLessThanOrEqual(70 + 1e-9);
    expect(box.heightMm).toBeLessThanOrEqual(25 + 1e-9);
    expect(box.heightMm).toBeCloseTo(el.rows * 3 * el.moduleMm, 9);
  });

  it('caixa pequena demais vira erro BARCODE_TOO_SMALL com as duas medidas', () => {
    const r = resolveLabel(one(DATA, 10, 3), {}, { dpi: 203 });
    expect(r.issues.some((i) => i.code === 'BARCODE_TOO_SMALL' && /10 x 3 mm/.test(i.message))).toBe(true);
  });

  it('modo rápido "size" (lotes compilados) ainda traz a matriz', () => {
    expect(resolved(one(), { barcodeDetail: 'size' }).matrix.length).toBeGreaterThan(0);
  });

  it('QR e DataMatrix seguem quadrados e com rowModules 1', () => {
    const t = addObject(createTemplate(40, 40), createBarcode2D({ id: 'q', symbology: 'QR', content: 'ABC', x: 2, y: 2, width: 20, height: 20 }));
    const el = resolveLabel(t, {}, { dpi: 203 }).elements[0]!;
    if (el.kind !== 'barcode2d') throw new Error(el.kind);
    expect(el.rowModules).toBe(1);
    const box = symbolBox2D(el);
    expect(box.widthMm).toBe(box.heightMm);
  });
});

describe('impressoras: imagem, sem comando nativo', () => {
  it('ZPL ^GFA, TSPL BITMAP, EPL GW — nenhum ^B7 / PDF417 / b nativo', () => {
    const t = one();
    const z = labelToCommands(zpl, resolveLabel(t, {}, { dpi: 203 })).commands;
    expect(z).toContain('^GFA,');
    expect(z).not.toContain('^B7');
    const ts = labelToCommands(tspl, resolveLabel(t, {}, { dpi: 203 })).commands;
    expect(ts).toContain('BITMAP ');
    expect(ts).not.toContain('PDF417');
    expect(labelToCommands(epl, resolveLabel(t, {}, { dpi: 203 })).commands).toContain('GW');
  });

  it('imagem = cols × módulo de largura e rows × 3 × módulo de altura', () => {
    const el = resolved(one());
    const r = raster2D(el);
    expect(r.bmp.width).toBe(el.cols * el.moduleDots);
    expect(r.bmp.height).toBe(el.rows * 3 * el.moduleDots);
  });

  it('girado 90°: a imagem gira junto', () => {
    const el = resolved(one(DATA, 25, 70, 90));
    const r = raster2D(el);
    expect(r.bmp.width).toBe(el.rows * 3 * el.moduleDots);
    expect(r.bmp.height).toBe(el.cols * el.moduleDots);
  });

  it('lote compilado com o dado vindo da planilha', () => {
    const c = compileLabel(one('{{CODIGO}}'), { language: 'TSPL', dpi: 203 });
    const out = c.render({ CODIGO: DATA }, 0);
    expect(out.issues.filter((i) => i.severity === 'error')).toEqual([]);
    expect(out.commands).toContain('BITMAP ');
  });
});

describe('prévia', () => {
  it('SVG usa linhas de 3 módulos de altura', () => {
    const el = resolved(one());
    const svg = labelToSvg(resolveLabel(one(), {}, { dpi: 203 }));
    const rowMm = Number((el.moduleMm * 3).toFixed(3));
    expect(svg).toMatch(new RegExp(`height="${String(rowMm).replace('.', '\\.')}`));
  });

  it('fromMatrix sem rowModules continua quadrado (compatível com QR/DataMatrix)', () => {
    const b = MonoBitmap.fromMatrix([1, 0, 0, 1], 2, 2, 3);
    expect([b.width, b.height]).toEqual([6, 6]);
    expect(MonoBitmap.fromMatrix([1, 0, 0, 1], 2, 2, 3, 3).height).toBe(18);
  });
});
