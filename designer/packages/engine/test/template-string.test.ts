import { describe, expect, it } from 'vitest';
import { compileString, renderTemplate, TemplateError } from '../src/data/template-string';

const ctx = (row: Record<string, unknown>, index = 0) => ({ row, index, now: new Date(2026, 9, 18) });

describe('string de template', () => {
  it('texto estático e campo dinâmico', () => {
    expect(renderTemplate('Loja XYZ', ctx({}))).toBe('Loja XYZ');
    expect(compileString('Loja XYZ').isStatic).toBe(true);
    expect(renderTemplate('Lote {{LOTE}}', ctx({ LOTE: 'L1' }))).toBe('Lote L1');
  });

  it('pipeline de transformações', () => {
    expect(renderTemplate('{{PRECO | currency}}', ctx({ PRECO: '1250.5' }))).toBe('R$ 1.250,50');
    expect(renderTemplate('{{PRECO * QTD | currency}}', ctx({ PRECO: '2,50', QTD: '3' }))).toBe('R$ 7,50');
    expect(renderTemplate('{{VALIDADE | date:DD/MM/YY}}', ctx({ VALIDADE: '2027-01-05' }))).toBe('05/01/27');
    expect(renderTemplate('{{PRODUTO | upper | truncate:10:…}}', ctx({ PRODUTO: 'Teclado Mecânico' }))).toBe('TECLADO M…');
    expect(renderTemplate('{{LOTE | default:SEM LOTE}}', ctx({ LOTE: '' }))).toBe('SEM LOTE');
    expect(renderTemplate('{{V | number:3}}', ctx({ V: '1.5' }))).toBe('1,500');
  });

  it('"||" é OU lógico, não pipe; argumento entre aspas pode conter "|"', () => {
    expect(renderTemplate('{{A || B}}', ctx({ A: '', B: 'x' }))).toBe('true');
    expect(renderTemplate('{{X | replace:"|":"-"}}', ctx({ X: 'a|b' }))).toBe('a-b');
  });

  it('erros apontam o marcador com problema', () => {
    expect(() => renderTemplate('{{PRECO | nada}}', ctx({ PRECO: '1' }))).toThrow(/desconhecida/);
    expect(() => compileString('{{ }}')).toThrow(TemplateError);
    expect(() => compileString('Preço {{A')).toThrow(/sem }}/);
    expect(() => renderTemplate('{{PRECO | currency}}', ctx({ PRECO: 'abc' }))).toThrow(/não é número/);
  });

  it('metadados para cache e mapeamento', () => {
    const c = compileString('{{SEQ(1)}} {{SKU}} {{PRECO | currency}}');
    expect(c.isVolatile).toBe(true);
    expect([...c.fields].sort()).toEqual(['PRECO', 'SKU']);
    expect(compileString('{{SKU}}')).toBe(compileString('{{SKU}}')); // cache
  });
});
