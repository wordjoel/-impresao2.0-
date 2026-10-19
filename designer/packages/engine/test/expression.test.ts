import { describe, expect, it } from 'vitest';
import { collectFields, evaluate, isVolatile, parseExpression, type EvalContext } from '../src/expr/expression';

const NOW = new Date(2026, 9, 18, 10, 30); // 18/10/2026 10:30, horário local

function run(src: string, row: Record<string, unknown> = {}, extra: Partial<EvalContext> = {}) {
  return evaluate(parseExpression(src), { row, index: 0, now: NOW, ...extra });
}

describe('expressões', () => {
  it('precedência aritmética', () => {
    expect(run('2 + 3 * 4')).toBe(14);
    expect(run('(2 + 3) * 4')).toBe(20);
    expect(run('10 % 4')).toBe(2);
    expect(run('-2 * 3')).toBe(-6);
  });

  it('números pt-BR vindos do CSV', () => {
    expect(run('PRECO * 2', { PRECO: '1.250,50' })).toBe(2501);
    expect(run('PRECO * QTD', { PRECO: '2,5', QTD: '4' })).toBe(10);
  });

  it('IF do exemplo do Bartender', () => {
    const src = 'IF(QTD > 10, "GRÁTIS", "PAGO")';
    expect(run(src, { QTD: '12' })).toBe('GRÁTIS');
    expect(run(src, { QTD: '5' })).toBe('PAGO');
  });

  it('lógica com palavras e símbolos, ternário e igualdade com =', () => {
    expect(run('QTD > 10 AND PRECO < 5', { QTD: '11', PRECO: '4' })).toBe(true);
    expect(run('QTD > 10 || PRECO < 5', { QTD: '1', PRECO: '9' })).toBe(false);
    expect(run('QTD > 10 ? "A" : "B"', { QTD: '3' })).toBe('B');
    expect(run('TIPO = "FRAGIL"', { TIPO: 'FRAGIL' })).toBe(true);
    expect(run('NOT ISBLANK(LOTE)', { LOTE: '' })).toBe(false);
  });

  it('& concatena; + é só numérico e explica o erro', () => {
    expect(run('"Lote " & LOTE', { LOTE: 'L1' })).toBe('Lote L1');
    expect(() => run('SKU + 1', { SKU: 'ABC' })).toThrow(/não é número/);
  });

  it('divisão por zero vira erro, mas IF só avalia o ramo usado', () => {
    expect(() => run('1 / 0')).toThrow(/Divisão por zero/);
    expect(run('IF(TRUE, 1, 1 / 0)')).toBe(1);
  });

  it('campos: ausente registra em missing, colchetes aceitam espaço, caixa é ignorada', () => {
    const missing = new Set<string>();
    expect(run('FOO', {}, { missing })).toBeNull();
    expect([...missing]).toEqual(['FOO']);
    expect(run('[Nome do Produto]', { 'Nome do Produto': 'Arroz' })).toBe('Arroz');
    expect(run('sku', { SKU: 'A1' })).toBe('A1');
  });

  it('não expõe propriedades do protótipo (sem eval, sem acesso a constructor)', () => {
    const missing = new Set<string>();
    expect(run('constructor', {}, { missing })).toBeNull();
    expect(missing.has('constructor')).toBe(true);
  });

  it('SEQ: auto-incremento pela posição no lote', () => {
    expect(run('SEQ(1, 1, 6)', {}, { index: 41 })).toBe('000042');
    expect(run('SEQ(100, 10)', {}, { index: 3 })).toBe('130');
  });

  it('datas: validade = hoje + 30 e formatação', () => {
    expect(run('DATEFMT(DATEADD(TODAY(), 30))')).toBe('17/11/2026');
    expect(run('DATEFMT(VALIDADE, "DD/MM/YY")', { VALIDADE: '2027-01-05' })).toBe('05/01/27');
    expect(() => run('DATEFMT(VALIDADE)', { VALIDADE: '31/02/2026' })).toThrow(/não é data/);
  });

  it('funções de texto e EAN13()', () => {
    expect(run('UPPER(LEFT(P, 3))', { P: 'açúcar' })).toBe('AÇÚ');
    expect(run('PROPER(P)', { P: 'arroz BRANCO' })).toBe('Arroz Branco');
    expect(run('EAN13("400638133393")')).toBe('4006381333931');
  });

  it('erros de sintaxe com mensagem útil', () => {
    expect(() => parseExpression('"abc')).toThrow(/aspas/);
    expect(() => parseExpression('FOO(')).toThrow();
    expect(() => run('NADA(1)')).toThrow(/desconhecida/);
    expect(() => parseExpression('('.repeat(100) + '1' + ')'.repeat(100))).toThrow(/aninhada/);
  });

  it('collectFields e isVolatile (base do cache de template)', () => {
    expect([...collectFields(parseExpression('IF(A > B, C, $index)'))].sort()).toEqual(['A', 'B', 'C']);
    expect(isVolatile(parseExpression('SEQ(1)'))).toBe(true);
    expect(isVolatile(parseExpression('A + 1'))).toBe(false);
  });
});
