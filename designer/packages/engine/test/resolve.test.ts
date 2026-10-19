import { describe, expect, it } from 'vitest';
import { dotsToMm } from '../src/geometry/units';
import { DEFAULT_BRAND } from '../src/model/brand';
import { createBarcode1D, createBox, createTemplate, createTextObject, type LabelTemplate } from '../src/model/objects';
import { addObject } from '../src/model/template-ops';
import { resolveLabel, templateFields } from '../src/render/resolve';
import { sampleIndices, validateBatch } from '../src/data/validate';
import { generateMockRows } from '../src/data/mock';

function template(): LabelTemplate {
  let t = createTemplate(50, 30);
  t = addObject(t, createTextObject({ id: 'txt', name: 'Produto', content: '{{PRODUTO}}', x: 2, y: 2, width: 30, height: 6 }));
  t = addObject(t, createBarcode1D({ id: 'bar', name: 'EAN', symbology: 'EAN13', content: '{{EAN}}', x: 5, y: 12, width: 40, height: 12 }));
  return t;
}

const row = { PRODUTO: 'Arroz Branco Tipo 1', EAN: '4006381333931' };

describe('Layout Resolver', () => {
  it('resolve texto dinâmico e código de barras com módulo em dots inteiros', () => {
    const r = resolveLabel(template(), row, { dpi: 203 });
    expect(r.issues.filter((i) => i.severity === 'error')).toEqual([]);
    const text = r.elements.find((e) => e.kind === 'text')!;
    expect(text.kind === 'text' && text.lines.join(' ')).toBe('Arroz Branco Tipo 1');
    expect(text.dynamic).toBe(true);
    const bar = r.elements.find((e) => e.kind === 'barcode1d')!;
    if (bar.kind !== 'barcode1d') throw new Error();
    // 40 mm a 203 dpi = 320 dots; 320 / 95 módulos = 3 dots por módulo.
    expect(bar.moduleDots).toBe(3);
    expect(bar.barsWidthMm).toBeCloseTo(dotsToMm(285, 203), 10);
    expect(bar.printerData).toBe('400638133393');
  });

  it('o mesmo template a 300 dpi escolhe outro módulo', () => {
    const bar = resolveLabel(template(), row, { dpi: 300 }).elements.find((e) => e.kind === 'barcode1d');
    expect(bar?.kind === 'barcode1d' && bar.moduleDots).toBe(4); // 472 dots / 95
  });

  it('EAN inválido vira elemento de erro e issue, sem derrubar a etiqueta', () => {
    const r = resolveLabel(template(), { ...row, EAN: '4006381333932' });
    expect(r.elements.find((e) => e.id === 'bar')?.kind).toBe('error');
    expect(r.issues.map((i) => i.code)).toContain('BARCODE_INVALID');
    expect(r.elements.find((e) => e.id === 'txt')?.kind).toBe('text');
  });

  it('campo ausente nos dados é apontado pelo nome', () => {
    const r = resolveLabel(template(), { EAN: row.EAN });
    expect(r.issues).toContainEqual(expect.objectContaining({ code: 'MISSING_FIELD', field: 'PRODUTO' }));
  });

  it('objeto fora da etiqueta e código que não cabe', () => {
    let t = template();
    t = addObject(t, createTextObject({ id: 'fora', name: 'Fora', x: 45, width: 10 }));
    t = addObject(t, createBarcode1D({ id: 'mini', name: 'Mini', content: 'ABCDEFGHIJ0123456789', x: 2, y: 2, width: 10 }));
    const codes = resolveLabel(t, row).issues.map((i) => `${i.code}:${i.objectId}`);
    expect(codes).toContain('OUT_OF_BOUNDS:fora');
    expect(codes).toContain('BARCODE_TOO_SMALL:mini');
  });

  it('kit de marca: fonte de título e cor da marca', () => {
    const brand = { ...DEFAULT_BRAND, colors: { ...DEFAULT_BRAND.colors, primary: '#C81F0E' }, fonts: { ...DEFAULT_BRAND.fonts, heading: { family: 'Roboto', bold: true } } };
    let t = createTemplate();
    t = addObject(t, createTextObject({ id: 't', brandFont: 'heading' }));
    t = addObject(t, createBox({ id: 'b', useBrandColor: true }));
    const els = resolveLabel(t, {}, { brand }).elements;
    expect(els[0]).toMatchObject({ family: 'Roboto', bold: true });
    expect(els[1]).toMatchObject({ color: '#C81F0E' });
  });

  it('lista os campos que o template usa', () => {
    expect(templateFields(template())).toEqual(['EAN', 'PRODUTO']);
  });
});

describe('Sanity Check', () => {
  it('amostra determinística cobre começo e fim', () => {
    const s = sampleIndices(1000, 10, 50);
    expect(s).toHaveLength(100);
    expect(s[0]).toBe(0);
    expect(s[s.length - 1]).toBe(999);
    expect(new Set(s).size).toBe(100);
    expect(sampleIndices(10)).toHaveLength(10);
  });

  it('acha SKUs vazios e EAN inválido, com as linhas de exemplo', () => {
    const rows = generateMockRows(40, 3).rows;
    [4, 9, 14, 19, 24].forEach((i) => (rows[i]!.PRODUTO = ''));
    rows[30]!.EAN = '7890000000001'; // verificador certo seria 0
    rows[31]!.EAN = 'abc';
    const report = validateBatch(template(), rows);
    expect(report.sampledRows).toBe(40);
    const empty = report.findings.find((f) => f.code === 'EMPTY_VALUE')!;
    expect(empty.count).toBe(5);
    expect(empty.exampleRows).toEqual([5, 10, 15, 20, 25]);
    expect(report.findings.find((f) => f.code === 'BARCODE_INVALID')?.count).toBe(2);
    expect(report.ok).toBe(false);
  });
});
