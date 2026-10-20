import { describe, expect, it } from 'vitest';
import {
  addObject,
  canonicalJson,
  createBarcode1D,
  createBarcode2D,
  createBox,
  createTemplate,
  createTextObject,
  sha256Hex,
  type LabelTemplate,
} from '@athos-label/engine';
import {
  assembleLabel,
  compileColetorPackage,
  compileLabel,
  fillSlot,
  NotPortableError,
  PackageInputError,
  SlotValueError,
  type ColetorLanguage,
  type PackageTarget,
  type TargetId,
} from '../src';

const NOW = new Date('2026-10-20T12:00:00Z');
const BOTH: TargetId[] = [
  { language: 'ZPL', dpi: 203 },
  { language: 'TSPL', dpi: 203 },
];

/** Etiqueta portável: texto fixo, caixa, texto com campo, EAN-13 e Code 128. */
function portable(): LabelTemplate {
  let t = createTemplate(60, 40, 'Gôndola');
  t = addObject(t, createTextObject({ id: 'loja', name: 'Loja', content: 'Loja Centro', x: 2, y: 1, width: 40, height: 5, fit: 'NONE' }));
  t = addObject(t, createTextObject({ id: 'lote', name: 'Lote', content: 'Lote {{LOTE}}', x: 2, y: 6, width: 50, height: 5, fit: 'NONE' }));
  t = addObject(t, createBarcode1D({ id: 'ean', name: 'EAN', symbology: 'EAN13', content: '{{EAN}}', x: 5, y: 12, width: 40, height: 12 }));
  t = addObject(t, createBox({ id: 'box', x: 0.5, y: 0.5, width: 59, height: 39, borderMm: 0.3 }));
  return t;
}

function withSku(t: LabelTemplate): LabelTemplate {
  t = addObject(t, createBarcode1D({ id: 'sku', name: 'SKU', symbology: 'CODE128', content: '{{SKU}}', x: 2, y: 26, width: 50, height: 8 }));
  return addObject(t, createBarcode2D({ id: 'qr', name: 'QR', content: 'https://ex.test/p/{{SKU}}', x: 46, y: 1, width: 12, height: 12 }));
}

async function compile(t: LabelTemplate, targets = BOTH, samples?: Record<string, string>) {
  return compileColetorPackage({ template: t, templateVersion: 3, templateHash: await sha256Hex(canonicalJson(t)), targets, samples, now: NOW });
}

async function problemsOf(t: LabelTemplate, targets = BOTH) {
  try {
    await compile(t, targets);
  } catch (e) {
    if (e instanceof NotPortableError) return e.problems.map((p) => `${p.objectId}:${p.reason}`).sort();
    throw e;
  }
  throw new Error('deveria recusar');
}

const target = (pkg: { targets: PackageTarget[] }, language: ColetorLanguage) => pkg.targets.find((t) => t.language === language)!;
const decode = (b: Uint8Array) => new TextDecoder('latin1').decode(b);

describe('pacote do coletor', () => {
  it('monta exatamente os bytes da impressão pelo PC (ZPL e TSPL, 1 e várias cópias)', async () => {
    const t = portable();
    const pkg = await compile(t);
    const row = { LOTE: 'A-77 "ç" ^~_\\', EAN: '7891234567895' };
    for (const tg of pkg.targets) {
      const pc = compileLabel(t, { language: tg.language, dpi: tg.dpi });
      // Com 2+ cópias o rodapé do pacote é idêntico ao do gerador.
      const fromPc = pc.encode(pc.render(row, 0, 4).commands);
      expect(assembleLabel(tg, row, 4)).toEqual(fromPc);
    }
    // 1 cópia: só o ZPL difere, e só por levar ^PQ1 (o gerador omite).
    const zpl = target(pkg, 'ZPL');
    const pc1 = compileLabel(t, { language: 'ZPL', dpi: 203 }).render(row, 0, 1).commands;
    expect(assembleLabel(zpl, row, 1)).toEqual(new TextEncoder().encode(pc1.replace('^XZ\n', '^PQ1\n^XZ\n')));
    const tsplPc = compileLabel(t, { language: 'TSPL', dpi: 203 });
    expect(assembleLabel(target(pkg, 'TSPL'), row, 1)).toEqual(tsplPc.encode(tsplPc.render(row, 0, 1).commands));
  });

  it('vetores de conformidade: pelo menos 2 por alvo, com acento e escapes, e batem com a montagem', async () => {
    const pkg = await compile(withSku(portable()));
    for (const tg of pkg.targets) {
      expect(tg.conformance.length).toBeGreaterThanOrEqual(2);
      const all = tg.conformance.map((v) => Object.values(v.values).join('')).join('');
      expect(all).toMatch(/ç/);
      expect(all).toMatch(tg.language === 'ZPL' ? /\^/ : /"/);
      for (const v of tg.conformance) expect(await sha256Hex(assembleLabel(tg, v.values, v.copies))).toBe(v.bytesSha256);
    }
  });

  it('metadados, campos e determinismo', async () => {
    const t = withSku(portable());
    const a = await compile(t, BOTH, { EAN: '7891234567895', SKU: 'ABC-1' });
    expect(a).toMatchObject({ format: 'athos-label-coletor', formatVersion: 1, templateId: t.id, name: 'Gôndola', templateVersion: 3, compiledAt: NOW.toISOString() });
    expect(a.fields.map((f) => [f.name, f.input, f.required])).toEqual([
      ['LOTE', 'ANY', true],
      ['EAN', 'SCAN', true],
      ['SKU', 'SCAN', true],
    ]);
    expect(a.fields.find((f) => f.name === 'EAN')).toMatchObject({ maxLength: 13, sample: '7891234567895' });
    expect(a.targets[0]!.conformance[0]!.values).toMatchObject({ EAN: '7891234567895', SKU: 'ABC-1' });
    expect(JSON.stringify(await compile(t, BOTH, { EAN: '7891234567895', SKU: 'ABC-1' }))).toBe(JSON.stringify(a));
  });

  it('Code 128 com módulo congelado: valor curto ou longo usa o mesmo ^BY', async () => {
    const pkg = await compile(withSku(portable()), [{ language: 'ZPL', dpi: 203 }]);
    const tg = pkg.targets[0]!;
    const sku = tg.slots.find((s) => s.kind === 'CODE128')!;
    const by = (v: string) => /\^BY(\d+),3,\d+\^BC/.exec(decode(assembleLabel(tg, { LOTE: '1', EAN: '7891234567895', SKU: v })))![1];
    expect(by('A')).toBe(by('X'.repeat(sku.maxLength)));
    expect(() => assembleLabel(tg, { LOTE: '1', EAN: '7891234567895', SKU: 'X'.repeat(sku.maxLength + 1) })).toThrow(SlotValueError);
  });

  it('lista TODOS os objetos que não vão ao coletor, com o motivo', async () => {
    let t = portable();
    t = addObject(t, createTextObject({ id: 'n', name: 'Contador', content: '{{SEQ(1,1,6)}}', fit: 'NONE' }));
    t = addObject(t, createTextObject({ id: 'idx', name: 'Posição', content: '{{$index}}', fit: 'NONE' }));
    t = addObject(t, createTextObject({ id: 'd', name: 'Validade', content: '{{DATEADD(TODAY(), 30)}}', fit: 'NONE' }));
    t = addObject(t, createTextObject({ id: 'p', name: 'Preço', content: '{{PRECO | currency}}', fit: 'NONE' }));
    t = addObject(t, createTextObject({ id: 'u', name: 'Nome', content: '{{UPPER(NOME)}}', fit: 'NONE' }));
    t = addObject(t, createTextObject({ id: 'w', name: 'Descrição', content: '{{DESC}}' })); // WRAP_SHRINK padrão
    t = addObject(t, createTextObject({ id: 'r', name: 'Girado', content: '{{X}}', fit: 'NONE', rotation: 45 }));
    t = addObject(t, createBarcode2D({ id: 'dm', name: 'DataMatrix', symbology: 'DATAMATRIX', content: '{{X}}' }));
    t = addObject(t, createBarcode1D({ id: 'c39', name: 'Code 39', symbology: 'CODE39', content: '{{X}}' }));
    t = addObject(t, createBarcode1D({ id: 'pre', name: 'EAN com texto', symbology: 'EAN13', content: '0{{X}}' }));
    t = addObject(t, createBarcode1D({ id: 'c180', name: 'Code 128 de ponta-cabeça', content: '{{X}}', rotation: 180 }));
    t = addObject(t, createTextObject({ id: 'tiny', name: 'Fonte maior que a caixa', content: '{{X}}', fit: 'NONE', height: 1 }));
    // Estático vai sempre, até DataMatrix: vira comando pronto.
    t = addObject(t, createBarcode2D({ id: 'dmfix', name: 'DataMatrix fixo', symbology: 'DATAMATRIX', content: 'FIXO123' }));
    expect(await problemsOf(t)).toEqual(
      [
        'n:SEQUENCE',
        'idx:SEQUENCE',
        'd:DATE_FUNCTION',
        'p:EXPRESSION',
        'u:EXPRESSION',
        'w:TEXT_FIT',
        'r:ROTATION',
        'dm:SYMBOLOGY',
        'c39:SYMBOLOGY',
        'pre:EXPRESSION',
        'c180:ROTATION',
        'tiny:TEXT_FIT',
      ].sort(),
    );
  });

  it('texto fixo fora do Windows-1252 só é problema no alvo TSPL', async () => {
    const t = addObject(portable(), createTextObject({ id: 'seta', name: 'Seta', content: 'Saída →', fit: 'NONE' }));
    expect(await problemsOf(t)).toEqual(['seta:CHARSET']);
    await expect(compile(t, [{ language: 'ZPL', dpi: 203 }])).resolves.toBeTruthy();
  });

  it('código que não cabe na caixa nem com módulo de 1 dot: BARCODE_SIZE', async () => {
    const t = addObject(portable(), createBarcode1D({ id: 'fino', name: 'Fino', content: '{{SKU}}', width: 5 }));
    expect(await problemsOf(t)).toEqual(['fino:BARCODE_SIZE']);
  });

  it('pedido inválido: alvo repetido, DPI, versão e hash', async () => {
    const t = portable();
    const hash = await sha256Hex(canonicalJson(t));
    const base = { template: t, templateVersion: 1, templateHash: hash, targets: BOTH };
    await expect(compileColetorPackage({ ...base, targets: [BOTH[0]!, BOTH[0]!] })).rejects.toThrow(PackageInputError);
    await expect(compileColetorPackage({ ...base, targets: [{ language: 'ZPL', dpi: 250 }] })).rejects.toThrow(/DPI/);
    await expect(compileColetorPackage({ ...base, targets: [{ language: 'EPL' as never, dpi: 203 }] })).rejects.toThrow(/EPL/);
    await expect(compileColetorPackage({ ...base, targets: [] })).rejects.toThrow(PackageInputError);
    await expect(compileColetorPackage({ ...base, templateVersion: 0 })).rejects.toThrow(/versão/);
    await expect(compileColetorPackage({ ...base, templateHash: '0'.repeat(64) })).rejects.toThrow(/templateHash/);
  });
});

describe('lacunas (o que o app valida)', () => {
  const text = { id: 's1', field: 'LOTE', kind: 'TEXT', transform: 'NONE', maxLength: 5 } as const;
  const ean = { id: 's2', field: 'EAN', kind: 'EAN13', transform: 'GS1_DATA', maxLength: 12 } as const;
  const err = (f: () => unknown) => {
    try {
      f();
    } catch (e) {
      return e instanceof SlotValueError ? e.code : 'outro';
    }
    return 'aceitou';
  };

  it('vazio = MISSING; controle, tamanho e verificador = INVALID (nunca corta)', () => {
    expect(err(() => fillSlot(text, undefined, 'ZPL'))).toBe('MISSING');
    expect(err(() => fillSlot(text, '', 'ZPL'))).toBe('MISSING');
    expect(err(() => fillSlot(text, 'a\nb', 'ZPL'))).toBe('INVALID');
    expect(err(() => fillSlot(text, '123456', 'ZPL'))).toBe('INVALID');
    expect(fillSlot(text, 'çãõé', 'TSPL')).toBe('çãõé');
    expect(err(() => fillSlot(ean, '7891234567890', 'ZPL'))).toBe('INVALID');
    expect(fillSlot(ean, '7891234567895', 'ZPL')).toBe('789123456789');
    expect(fillSlot(ean, '789123456789', 'TSPL')).toBe('789123456789');
  });

  it('escape: ZPL ^ ~ _ \\ em hexa; TSPL aspas e recusa fora do Windows-1252', () => {
    const t = { ...text, maxLength: 20 };
    expect(fillSlot(t, 'a^b~c_d\\', 'ZPL')).toBe('a_5Eb_7Ec_5Fd_5C');
    expect(fillSlot(t, 'diz "oi"', 'TSPL')).toBe('diz \\["]oi\\["]');
    expect(fillSlot(t, '€ 5', 'TSPL')).toBe('\u0080 5');
    expect(err(() => fillSlot(t, 'seta →', 'TSPL'))).toBe('INVALID');
    expect(fillSlot(t, 'seta →', 'ZPL')).toBe('seta →');
    expect(err(() => fillSlot(t, '́abc', 'TSPL'))).toBe('INVALID');
  });

  it('cópias fora de 1..9999 são recusadas', async () => {
    const pkg = await compile(portable(), [{ language: 'TSPL', dpi: 203 }]);
    const v = pkg.targets[0]!.conformance[0]!.values;
    expect(() => assembleLabel(pkg.targets[0]!, v, 0)).toThrow(RangeError);
    expect(() => assembleLabel(pkg.targets[0]!, v, 10000)).toThrow(RangeError);
    expect(decode(assembleLabel(pkg.targets[0]!, v, 12))).toMatch(/PRINT 1,12\r\n$/);
  });
});
