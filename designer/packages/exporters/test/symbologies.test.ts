import { describe, expect, it } from 'vitest';
import { addObject, createBarcode1D, createTemplate, resolveLabel, type LabelTemplate, type Symbology1D } from '@athos-label/engine';
import { compileLabel, epl, labelToCommands, labelToSvg, raster1D, tspl, zpl } from '../src';

/** Passo 2 do plano (2026-10-19): EAN-8 nativo; ITF-14 e GS1-128 como imagem + texto legível. */

function one(symbology: Symbology1D, content: string, rotation = 0, width = 76): LabelTemplate {
  return addObject(createTemplate(80, 40), createBarcode1D({ id: 'b', symbology, content, x: 2, y: 2, width, height: 20, rotation }));
}

const cmds = (gen: typeof zpl, t: LabelTemplate) => labelToCommands(gen, resolveLabel(t, {}, { dpi: 203 }));

describe('EAN-8 nativo (a impressora calcula o verificador)', () => {
  it('ZPL ^B8, TSPL "EAN8", EPL E80 — todos com 7 dígitos', () => {
    const t = one('EAN8', '96385074', 0, 40);
    expect(cmds(zpl, t).commands).toMatch(/\^BY\d+,3,\d+\^B8N,\d+,Y,N\^FH_\^FD9638507\^FS/);
    expect(cmds(tspl, t).commands).toMatch(/BARCODE \d+,\d+,"EAN8",\d+,1,0,\d+,\d+,"9638507"\r\n/);
    expect(cmds(epl, t).commands).toMatch(/B\d+,\d+,0,E80,\d+,\d+,\d+,B,"9638507"\n/);
  });
});

describe('ITF-14 e GS1-128 como imagem', () => {
  const cases: [Symbology1D, string, string][] = [
    ['ITF14', '1001234567890', '10012345678902'],
    ['GS1_128', '(01)10012345678902(10)LOTE1', '(01)10012345678902(10)LOTE1'],
  ];

  it.each(cases)('%s: ZPL ^GFA + texto, sem comando de barras nativo', (sym, content, human) => {
    const out = cmds(zpl, one(sym, content)).commands;
    expect(out).toContain('^GFA,');
    expect(out).not.toMatch(/\^B[C2E8UQX3]/);
    expect(out).toContain(`^FD${human.replace(/[\^~_\\]/g, '')}^FS`);
  });

  it.each(cases)('%s: TSPL BITMAP + BLOCK; EPL GW + texto', (sym, content, human) => {
    const t = one(sym, content);
    const ts = cmds(tspl, t).commands;
    expect(ts).toContain('BITMAP ');
    expect(ts).not.toContain('BARCODE ');
    expect(ts).toContain(`"${human}"`);
    const ep = cmds(epl, t).commands;
    expect(ep).toContain('GW');
    expect(ep).toContain(`"${human}"`);
  });

  it('largura da imagem = módulos × módulo em dots; altura = altura das barras', () => {
    const el = resolveLabel(one('ITF14', '1001234567890'), {}, { dpi: 203 }).elements[0]!;
    if (el.kind !== 'barcode1d') throw new Error(el.kind);
    const r = raster1D(el, 203);
    expect(r.bars.width).toBe(el.moduleCount * el.moduleDots);
    expect(r.bars.height).toBe(Math.round((el.barHeightMm * 203) / 25.4));
    expect(r.bars.get(0, 0)).toBe(el.modules[0] === 1);
  });

  it('girado 90°: imagem gira junto (largura e altura trocam)', () => {
    const el = resolveLabel(one('ITF14', '1001234567890', 90, 36), {}, { dpi: 203 }).elements[0]!;
    if (el.kind !== 'barcode1d') throw new Error(el.kind);
    const r = raster1D(el, 203);
    expect(r.bars.width).toBe(Math.round((el.barHeightMm * 203) / 25.4));
    expect(r.bars.height).toBe(el.moduleCount * el.moduleDots);
    expect(r.text?.rotation).toBe(90);
  });

  it('sem texto legível: só a imagem', () => {
    const t = addObject(
      createTemplate(80, 40),
      createBarcode1D({ id: 'b', symbology: 'ITF14', content: '1001234567890', x: 2, y: 2, width: 76, height: 20, humanReadable: false }),
    );
    expect(cmds(tspl, t).commands).not.toContain('BLOCK');
  });

  it('lote compilado (modo rápido) com GTIN vindo dos dados ainda gera a imagem', () => {
    const t = one('ITF14', '{{GTIN}}');
    const c = compileLabel(t, { language: 'TSPL', dpi: 203 });
    const out = c.render({ GTIN: '1001234567890' }, 0);
    expect(out.issues.filter((i) => i.severity === 'error')).toEqual([]);
    expect(out.commands).toContain('BITMAP ');
    expect(out.commands).toContain('"10012345678902"');
  });

  it('SVG da prévia desenha as mesmas barras (sem caminho especial)', () => {
    const svg = labelToSvg(resolveLabel(one('GS1_128', '(01)10012345678902'), {}, { dpi: 203 }));
    expect(svg).toContain('<rect');
    expect(svg).toContain('(01)10012345678902');
  });
});
