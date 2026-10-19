import { describe, expect, it } from 'vitest';
import { applyMapping, parseCsv, parseJsonRecords, suggestMapping } from '../src/data/sources';
import { generateMockRows } from '../src/data/mock';
import { sanitizeRows, sanitizeValue } from '../src/data/sanitize';
import { isValidEan13 } from '../src/barcode/checksum';

describe('CSV', () => {
  it('BOM do Excel, ";" detectado, aspas, aspas duplicadas, quebra dentro de aspas, CRLF, linha vazia', () => {
    const text = '﻿SKU;Produto;Preço\r\n"A1";"Arroz ""Tipo 1""";10,50\r\nA2;"Feijão\nCarioca";7\r\n\r\n';
    const ds = parseCsv(text);
    expect(ds.headers).toEqual(['SKU', 'Produto', 'Preço']);
    expect(ds.rows).toHaveLength(2);
    expect(ds.rows[0]).toEqual({ SKU: 'A1', Produto: 'Arroz "Tipo 1"', Preço: '10,50' });
    expect(ds.rows[1]!.Produto).toBe('Feijão\nCarioca');
  });

  it('cabeçalho repetido ou vazio ganha nome único; linha curta completa com vazio', () => {
    const ds = parseCsv('a,a,\n1,2');
    expect(ds.headers).toEqual(['a', 'a_2', 'COLUNA_3']);
    expect(ds.rows[0]).toEqual({ a: '1', a_2: '2', COLUNA_3: '' });
  });

  it('aspas sem fechamento é erro, não dado corrompido', () => {
    expect(() => parseCsv('a\n"x')).toThrow(/aspas/);
  });
});

describe('JSON e mapeamento', () => {
  it('aceita {data:[...]}, null vira vazio e objeto vira JSON', () => {
    const ds = parseJsonRecords('{"data":[{"SKU":1,"X":null},{"SKU":"2","Y":{"a":1}}]}');
    expect(ds.headers).toEqual(['SKU', 'X', 'Y']);
    expect(ds.rows[0]).toEqual({ SKU: '1', X: '', Y: '' });
    expect(ds.rows[1]!.Y).toBe('{"a":1}');
    expect(() => parseJsonRecords('{"a":1}')).toThrow(/lista/);
  });

  it('sugere mapeamento ignorando acento, caixa e separadores', () => {
    expect(suggestMapping(['PRECO', 'SKU', 'NOME_PRODUTO'], ['Preço', 'sku', 'Nome Produto'])).toEqual({
      PRECO: 'Preço',
      SKU: 'sku',
      NOME_PRODUTO: 'Nome Produto',
    });
    expect(applyMapping({ Preço: '1' }, { PRECO: 'Preço' })).toEqual({ Preço: '1', PRECO: '1' });
  });
});

describe('dados fictícios', () => {
  it('determinísticos pela semente e com EAN-13 válido', () => {
    const a = generateMockRows(20, 7);
    expect(generateMockRows(20, 7).rows).toEqual(a.rows);
    expect(generateMockRows(20, 8).rows).not.toEqual(a.rows);
    expect(a.rows.every((r) => isValidEan13(r.EAN!))).toBe(true);
    expect(a.rows.every((r) => r.VALIDADE! > r.FABRICACAO!)).toBe(true);
  });
});

describe('saneamento', () => {
  it('controle, espaços, "null" de planilha e Unicode decomposto', () => {
    expect(sanitizeValue('  Arroz\u0007   Branco  ')).toBe('Arroz Branco');
    expect(sanitizeValue('null')).toBe('');
    expect(sanitizeValue('#N/A')).toBe('');
    expect(sanitizeValue('ç')).toBe('ç');
    const r = sanitizeRows([{ A: ' x ', B: 'ok' }]);
    expect(r.rows[0]).toEqual({ A: 'x', B: 'ok' });
    expect(r.changedByColumn).toEqual({ A: 1 });
  });
});
