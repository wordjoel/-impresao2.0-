import { describe, expect, it } from 'vitest';
import { ean13CheckDigit, isValidEan13, upcaCheckDigit } from '../src/barcode/checksum';
import { BarcodeError, encode1D, encode2D, moduleDots, normalize1D } from '../src/barcode/encode';

describe('dígito verificador (valores de referência GS1)', () => {
  it('EAN-13 e UPC-A', () => {
    expect(ean13CheckDigit('400638133393')).toBe('1'); // 4006381333931
    expect(isValidEan13('5901234123457')).toBe(true);
    expect(isValidEan13('5901234123458')).toBe(false);
    expect(upcaCheckDigit('03600029145')).toBe('2'); // 036000291452
  });
});

describe('normalização para a impressora', () => {
  it('EAN-13: 12 dígitos completam; 13 com verificador errado é recusado', () => {
    expect(normalize1D('EAN13', '400638133393')).toEqual({ printerData: '400638133393', full: '4006381333931' });
    expect(normalize1D('EAN13', '4006381333931').printerData).toBe('400638133393');
    expect(() => normalize1D('EAN13', '4006381333932')).toThrow(/verificador/);
    expect(() => normalize1D('EAN13', '123')).toThrow(BarcodeError);
  });

  it('Code 128 recusa acento; Code 39 vira maiúscula', () => {
    expect(() => normalize1D('CODE128', 'Ação')).toThrow(/ASCII/);
    expect(normalize1D('CODE39', 'abc-1').printerData).toBe('ABC-1');
    expect(() => normalize1D('CODE39', 'a_b')).toThrow(/Code 39/);
  });
});

describe('codificação', () => {
  it('EAN-13 e UPC-A têm 95 módulos', () => {
    expect(encode1D('EAN13', '4006381333931').modules.length).toBe(95);
    expect(encode1D('UPCA', '036000291452').modules.length).toBe(95);
  });

  it('Code 128: 11 módulos por símbolo + 13 do stop, começando em barra', () => {
    const m = encode1D('CODE128', 'ABC123').modules;
    expect((m.length - 13) % 11).toBe(0);
    expect(m[0]).toBe(1);
    expect(m[m.length - 1]).toBe(1);
  });

  it('QR versão 1 = 21x21; DataMatrix quadrado', () => {
    const qr = encode2D('QR', 'HELLO 123', 'M');
    expect([qr.rows, qr.cols, qr.matrix.length]).toEqual([21, 21, 441]);
    const dm = encode2D('DATAMATRIX', 'ABC123');
    expect(dm.rows).toBe(dm.cols);
    expect(() => encode2D('QR', '')).toThrow(/vazio/);
  });

  it('módulo em dots inteiros, nunca maior que o espaço', () => {
    expect(moduleDots(320, 95)).toBe(3);
    expect(moduleDots(50, 101)).toBe(0);
    expect(moduleDots(5000, 95)).toBe(10);
  });
});
