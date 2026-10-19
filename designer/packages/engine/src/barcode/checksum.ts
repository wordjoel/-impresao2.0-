/**
 * Dígito verificador GS1 (mod 10, pesos 3/1 a partir da direita). O mesmo algoritmo serve a
 * EAN-8, EAN-13, UPC-A, GTIN-14 (ITF-14, AI 01/02) e SSCC (AI 00).
 */
export function gs1Check(digits: string): string {
  let sum = 0;
  for (let i = 0; i < digits.length; i++) {
    const fromRight = digits.length - 1 - i;
    sum += Number(digits[i]) * (fromRight % 2 === 0 ? 3 : 1);
  }
  return String((10 - (sum % 10)) % 10);
}

export function ean13CheckDigit(twelve: string): string {
  if (!/^\d{12}$/.test(twelve)) throw new Error('EAN-13 precisa de 12 dígitos para calcular o verificador');
  return gs1Check(twelve);
}

export function upcaCheckDigit(eleven: string): string {
  if (!/^\d{11}$/.test(eleven)) throw new Error('UPC-A precisa de 11 dígitos para calcular o verificador');
  return gs1Check(eleven);
}

export function isValidEan13(v: string): boolean {
  return /^\d{13}$/.test(v) && gs1Check(v.slice(0, 12)) === v[12];
}

export function isValidUpcA(v: string): boolean {
  return /^\d{12}$/.test(v) && gs1Check(v.slice(0, 11)) === v[11];
}

/** Qualquer número GS1 com verificador no fim (8, 12, 13, 14 ou 18 dígitos). */
export function isValidGs1Number(v: string): boolean {
  return /^\d{8}$|^\d{12,14}$|^\d{18}$/.test(v) && gs1Check(v.slice(0, -1)) === v[v.length - 1];
}
