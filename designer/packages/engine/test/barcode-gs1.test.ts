import { describe, expect, it } from 'vitest';
import { gs1Check, isValidGs1Number } from '../src/barcode/checksum';
import { BarcodeError, encode1D, moduleCount1D, normalize1D, RASTER_1D } from '../src/barcode/encode';
import { addObject, createBarcode1D, createTemplate, resolveLabel } from '../src';

/** Passo 2 do plano (2026-10-19): EAN-8, ITF-14 e GS1-128. Valores de referência da norma/GS1. */

describe('verificador GS1 genérico', () => {
  it('EAN-8, GTIN-14 e SSCC', () => {
    expect(gs1Check('9638507')).toBe('4'); // 96385074
    expect(gs1Check('1001234567890')).toBe('2'); // 10012345678902
    expect(isValidGs1Number('96385074')).toBe(true);
    expect(isValidGs1Number('10012345678902')).toBe(true);
    expect(isValidGs1Number('10012345678903')).toBe(false);
    expect(isValidGs1Number('106141411234567897')).toBe(true); // SSCC de exemplo da GS1
  });
});

describe('EAN-8', () => {
  it('7 dígitos: calcula o verificador; a impressora recebe 7', () => {
    expect(normalize1D('EAN8', '9638507')).toEqual({ printerData: '9638507', full: '96385074' });
  });
  it('8 dígitos com verificador certo passa; errado é recusado com o esperado na mensagem', () => {
    expect(normalize1D('EAN8', '96385074').printerData).toBe('9638507');
    expect(() => normalize1D('EAN8', '96385075')).toThrow(/esperado 4/);
    expect(() => normalize1D('EAN8', '123')).toThrow(BarcodeError);
  });
  it('67 módulos, igual na contagem rápida e na codificação real', () => {
    expect(encode1D('EAN8', '9638507').modules.length).toBe(67);
    expect(moduleCount1D('EAN8', '9638507').count).toBe(67);
  });
});

describe('ITF-14', () => {
  it('13 dígitos ganham o verificador; 14 com verificador errado são recusados', () => {
    expect(normalize1D('ITF14', '1001234567890').full).toBe('10012345678902');
    expect(normalize1D('ITF14', '1 00 12345 67890 2').full).toBe('10012345678902');
    expect(() => normalize1D('ITF14', '10012345678903')).toThrow(/esperado 2/);
    expect(() => normalize1D('ITF14', 'ABC')).toThrow(BarcodeError);
  });
  it('codifica, começa com barra e conta igual nos dois caminhos', () => {
    const enc = encode1D('ITF14', '10012345678902');
    expect(enc.modules[0]).toBe(1);
    expect(moduleCount1D('ITF14', '10012345678902').count).toBe(enc.modules.length);
  });
});

describe('GS1-128', () => {
  const ok = '(01)10012345678902(17)261231(10)LOTE1';

  it('aceita o formato (AI)dado e mantém o texto legível com parênteses', () => {
    expect(normalize1D('GS1_128', ok).full).toBe(ok);
    expect(normalize1D('GS1_128', ' (01)10012345678902 (10)LOTE1 ').full).toBe('(01)10012345678902(10)LOTE1');
    expect(encode1D('GS1_128', ok).modules.length).toBeGreaterThan(0);
  });
  it('recusa forma errada, GTIN com verificador errado, AI 01 curto e dado longo demais', () => {
    expect(() => normalize1D('GS1_128', '0110012345678902')).toThrow(/formato/);
    expect(() => normalize1D('GS1_128', '(01)10012345678903')).toThrow(/esperado 2/);
    expect(() => normalize1D('GS1_128', '(01)123')).toThrow(/14 dígitos/);
    // 22 + 22 + 4 = 48 é o máximo e passa; 1 caractere a mais é recusado.
    expect(normalize1D('GS1_128', `(10)${'A'.repeat(20)}(21)${'B'.repeat(20)}(240)X`).full).toContain('(240)X');
    expect(() => normalize1D('GS1_128', `(10)${'A'.repeat(20)}(21)${'B'.repeat(20)}(240)XY`)).toThrow(/48/);
  });
  it('AI que o lint GS1 do codificador recusa vira erro de código de barras (não exceção solta)', () => {
    expect(() => encode1D('GS1_128', '(17)991399')).toThrow(BarcodeError); // mês 13
  });
});

describe('resolver', () => {
  it('simbologias desenhadas como imagem têm barras até no modo rápido "size"', () => {
    expect([...RASTER_1D].sort()).toEqual(['GS1_128', 'ITF14']);
    const t = addObject(createTemplate(80, 40), createBarcode1D({ id: 'b', symbology: 'ITF14', content: '{{GTIN}}', x: 2, y: 2, width: 76, height: 20 }));
    const el = resolveLabel(t, { GTIN: '1001234567890' }, { barcodeDetail: 'size' }).elements[0]!;
    expect(el.kind).toBe('barcode1d');
    if (el.kind === 'barcode1d') {
      expect(el.modules.length).toBe(el.moduleCount);
      expect(el.humanText).toBe('10012345678902');
    }
  });
  it('dado inválido vira erro na validação, não etiqueta errada', () => {
    const t = addObject(createTemplate(80, 40), createBarcode1D({ id: 'b', symbology: 'EAN8', content: '96385075', x: 2, y: 2, width: 40, height: 15 }));
    const r = resolveLabel(t, {});
    expect(r.elements[0]!.kind).toBe('error');
    expect(r.issues.some((i) => i.code === 'BARCODE_INVALID')).toBe(true);
  });
});
