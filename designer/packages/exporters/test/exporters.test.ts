import { describe, expect, it } from 'vitest';
import { PDFDocument } from 'pdf-lib';
import {
  addObject,
  createBarcode1D,
  createBarcode2D,
  createBox,
  createImageObject,
  createTemplate,
  createTextObject,
  generateMockRows,
  MonoBitmap,
  resolveLabel,
  type LabelTemplate,
  type PrinterLanguage,
} from '@athos-label/engine';
import {
  compileLabel,
  epl,
  GENERATORS,
  labelsToPdf,
  labelToCommands,
  labelToSvg,
  pickEplFont,
  runs,
  tspl,
  zpl,
  zplEscape,
} from '../src';

function template(): LabelTemplate {
  let t = createTemplate(50, 30);
  t = addObject(t, createTextObject({ id: 'loja', name: 'Loja', content: 'Loja XYZ', x: 2, y: 1, width: 30, height: 4 }));
  t = addObject(t, createTextObject({ id: 'preco', name: 'Preço', content: 'Preço {{PRECO | currency}}', x: 2, y: 5, width: 40, height: 5 }));
  t = addObject(t, createBarcode1D({ id: 'ean', name: 'EAN', symbology: 'EAN13', content: '{{EAN}}', x: 5, y: 12, width: 40, height: 12 }));
  t = addObject(t, createBarcode2D({ id: 'qr', name: 'QR', content: '{{SKU}}', x: 38, y: 1, width: 10, height: 10 }));
  t = addObject(t, createBox({ id: 'box', x: 0.5, y: 0.5, width: 49, height: 29, borderMm: 0.3 }));
  return t;
}

const row = { PRECO: '9.9', EAN: '4006381333931', SKU: 'SKU-1' };

describe('ZPL', () => {
  it('escape correto do ^FH (não existe "^^" em ZPL)', () => {
    expect(zplEscape('a^b~c_d\\e')).toBe('a_5Eb_7Ec_5Fd_5Ce');
  });

  it('etiqueta completa: dimensão em dots, UTF-8, ^BY do resolver, EAN sem verificador, ^PQ', () => {
    const out = labelToCommands(zpl, resolveLabel(template(), row, { dpi: 203 }), { copies: 3 }).commands;
    expect(out.startsWith('^XA\n^CI28\n^PW400\n^LL240\n^LH0,0\n')).toBe(true);
    expect(out).toContain('^FH_^FDPreço R$ 9,90^FS');
    expect(out).toContain('^A0N,');
    expect(out).toMatch(/\^FO40,96\^BY3,3,\d+\^BEN,\d+,Y,N\^FH_\^FD400638133393\^FS/);
    expect(out).toContain('^BQN,2,');
    expect(out).toContain('^GB392,232,2,B,0^FS');
    expect(out.endsWith('^PQ3\n^XZ\n')).toBe(true);
  });

  it('rotação 90° vira orientação R; 45° gera alerta e arredonda', () => {
    let t = createTemplate(50, 30);
    t = addObject(t, createTextObject({ id: 'r', content: 'Girado', x: 10, y: 5, width: 20, height: 5, rotation: 90 }));
    t = addObject(t, createTextObject({ id: 'd', content: 'Diagonal', x: 20, y: 5, width: 20, height: 5, rotation: 45 }));
    const r = labelToCommands(zpl, resolveLabel(t, {}));
    expect(r.commands).toContain('^A0R,');
    const fo = /\^FO(\d+),(\d+)\^A0R/.exec(r.commands)!;
    expect(Number(fo[1])).toBeLessThan(80); // caixa visível fica à esquerda do canto (x=10 mm = 80 dots)
    expect(r.issues).toContainEqual(expect.objectContaining({ code: 'UNSUPPORTED', objectId: 'd' }));
  });
});

describe('TSPL (TSC)', () => {
  it('cabeçalho sem GAP, fonte 0 em pontos, aspas escapadas, Windows-1252 e PRINT com cópias', () => {
    let t = createTemplate(50, 30);
    t = addObject(t, createTextObject({ id: 't', content: 'Tela 5" Preço → ok', x: 2, y: 2, width: 46, height: 5 }));
    const r = labelToCommands(tspl, resolveLabel(t, {}), { copies: 2 });
    expect(r.commands.startsWith('SIZE 50 mm,30 mm\r\nCODEPAGE 1252\r\nCLS\r\n')).toBe(true);
    expect(r.commands).not.toContain('GAP');
    expect(r.commands).toContain(',"0",0,');
    expect(r.commands).toContain('"Tela 5\\["] Preço ? ok"');
    expect(r.issues).toContainEqual(expect.objectContaining({ code: 'UNSUPPORTED' }));
    expect(tspl.encode(r.commands)).toContain(0xe7); // ç em 1 byte
    expect(r.commands.endsWith('PRINT 1,2\r\n')).toBe(true);
  });

  it('código de barras e BITMAP com bits invertidos (regra validada no Print Bridge)', () => {
    let t = createTemplate(50, 30);
    t = addObject(t, createBarcode1D({ id: 'e', symbology: 'EAN13', content: '4006381333931', x: 5, y: 12, width: 40, height: 12 }));
    const black = new MonoBitmap(1, 1);
    black.set(0, 0, true);
    t = addObject(t, createImageObject({ id: 'i', src: 'data:image/png;base64,', mono: black.toData(), x: 0, y: 0, width: 1, height: 1 }));
    const cmds = labelToCommands(tspl, resolveLabel(t, {})).commands;
    expect(cmds).toMatch(/BARCODE 40,96,"EAN13",\d+,1,0,3,3,"400638133393"\r\n/);
    const bytes = tspl.encode(cmds);
    const marker = new TextEncoder().encode('BITMAP 0,0,1,8,0,');
    const at = indexOf(bytes, marker);
    expect(at).toBeGreaterThanOrEqual(0);
    // 8x8 todo preto -> 0xFF por linha -> invertido = 0x00
    expect([...bytes.subarray(at + marker.length, at + marker.length + 8)]).toEqual([0, 0, 0, 0, 0, 0, 0, 0]);
  });
});

describe('EPL2', () => {
  it('escolhe fonte nativa no empate (nítida, não ampliada)', () => {
    expect(pickEplFont(24, 203)).toMatchObject({ font: 4, mult: 1 });
    expect(pickEplFont(48, 203)).toMatchObject({ font: 5, mult: 1 });
    expect(pickEplFont(72, 203)).toMatchObject({ mult: 3 });
  });

  it('texto centralizado com largura fixa calculada; QR vira gráfico GW', () => {
    let t = createTemplate(50, 30);
    t = addObject(t, createTextObject({ id: 't', content: 'ABC', x: 0, y: 0, width: 30, height: 4, alignment: 'CENTER', font: { family: 'Helvetica', sizeMm: 3, bold: false, italic: false }, fit: 'NONE' }));
    t = addObject(t, createBarcode2D({ id: 'q', content: 'X', x: 35, y: 2, width: 10, height: 10 }));
    const cmds = labelToCommands(epl, resolveLabel(t, {})).commands;
    // 3 mm = 24 dots -> fonte 4 (14x24); "ABC" = 42 dots; largura 240 -> sobra 198 -> 99 de cada lado.
    expect(cmds).toMatch(/\nA99,\d+,0,4,1,1,N,"ABC"\n/);
    expect(cmds).toMatch(/\nGW\d+,\d+,\d+,\d+,/);
    expect(cmds.endsWith('P1\n')).toBe(true);
  });
});

describe('cache de template', () => {
  it.each<PrinterLanguage>(['ZPL', 'TSPL', 'EPL'])('%s: compilado == geração direta, linha a linha', (language) => {
    const t = template();
    const compiled = compileLabel(t, { language, dpi: 203 });
    expect(compiled.stats).toEqual({ staticObjects: 2, dynamicObjects: 3 });
    for (const [i, r] of generateMockRows(20, 5).rows.entries()) {
      const direct = labelToCommands(GENERATORS[language], resolveLabel(t, r, { dpi: 203, index: i }), { copies: 1 });
      expect(compiled.render(r, i).commands).toBe(direct.commands);
    }
  });

  it('10.000 etiquetas ZPL em menos de 3 s (medido: ~0,2 s)', () => {
    const t = template();
    const rows = generateMockRows(10_000, 1).rows;
    const compiled = compileLabel(t, { language: 'ZPL', dpi: 203 });
    const start = performance.now();
    let size = 0;
    rows.forEach((r, i) => (size += compiled.render(r, i).commands.length));
    const ms = performance.now() - start;
    console.log(`10.000 etiquetas ZPL: ${Math.round(ms)} ms, ${(size / 1e6).toFixed(1)} MB`);
    expect(ms).toBeLessThan(3_000);
  });
});

describe('SVG e PDF', () => {
  it('SVG em mm, com texto escapado e uma barra por sequência de módulos', () => {
    let t = template();
    t = addObject(t, createTextObject({ id: 'amp', content: 'A & B <c>', x: 2, y: 25, width: 30, height: 4 }));
    const label = resolveLabel(t, row);
    const svg = labelToSvg(label);
    expect(svg).toContain('viewBox="0 0 50 30"');
    expect(svg).toContain('A &#38; B &#60;c&#62;');
    const bar = label.elements.find((e) => e.kind === 'barcode1d')!;
    if (bar.kind !== 'barcode1d') throw new Error();
    const barRects = svg.split('<g fill="#000">')[1]!.split('<text')[0]!.match(/<rect /g)!.length;
    expect(barRects).toBe(runs(bar.modules).length);
  });

  it('PDF com uma página por etiqueta, no tamanho físico', async () => {
    const labels = generateMockRows(3, 2).rows.map((r) => resolveLabel(template(), { ...r, PRECO: '1' }));
    const bytes = await labelsToPdf(labels);
    expect(new TextDecoder().decode(bytes.subarray(0, 5))).toBe('%PDF-');
    const doc = await PDFDocument.load(bytes);
    expect(doc.getPageCount()).toBe(3);
    const { width, height } = doc.getPage(0).getSize();
    expect(width).toBeCloseTo((50 * 72) / 25.4, 3);
    expect(height).toBeCloseTo((30 * 72) / 25.4, 3);
  });
});

function indexOf(hay: Uint8Array, needle: Uint8Array): number {
  outer: for (let i = 0; i <= hay.length - needle.length; i++) {
    for (let j = 0; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer;
    return i;
  }
  return -1;
}
