import type { DataRow } from './sources';

/**
 * Camada de saneamento: limpa os dados ANTES do motor, para que um CSV sujo não imprima
 * "null", espaços duplos ou caracteres de controle que a impressora interpreta como comando.
 */
export interface SanitizeOptions {
  trim: boolean;
  collapseSpaces: boolean;
  /** Remove caracteres de controle (exceto \n, usado em quebra de linha explícita). */
  stripControl: boolean;
  /** "null", "undefined", "NaN", "#N/A" vindos de exportações de planilha viram vazio. */
  nullishToEmpty: boolean;
  /** Une formas Unicode equivalentes (NFC): "ç" decomposto vira o caractere único. */
  normalizeUnicode: boolean;
}

export const DEFAULT_SANITIZE: SanitizeOptions = {
  trim: true,
  collapseSpaces: true,
  stripControl: true,
  nullishToEmpty: true,
  normalizeUnicode: true,
};

const NULLISH = new Set(['null', 'undefined', 'nan', '#n/a', '#n/d', 'n/a']);

export function sanitizeValue(v: string, o: SanitizeOptions = DEFAULT_SANITIZE): string {
  let s = v;
  if (o.normalizeUnicode) s = s.normalize('NFC');
  // eslint-disable-next-line no-control-regex
  if (o.stripControl) s = s.replace(/[\u0000-\u0009\u000B-\u001F\u007F]/g, '');
  if (o.collapseSpaces) s = s.replace(/[ \t ]+/g, ' ');
  if (o.trim) s = s.trim();
  if (o.nullishToEmpty && NULLISH.has(s.toLowerCase())) s = '';
  return s;
}

export interface SanitizeReport {
  rows: DataRow[];
  /** Quantas células foram alteradas, por coluna. */
  changedByColumn: Record<string, number>;
}

export function sanitizeRows(rows: readonly DataRow[], o: SanitizeOptions = DEFAULT_SANITIZE): SanitizeReport {
  const changedByColumn: Record<string, number> = {};
  const out = rows.map((r) => {
    const clean: DataRow = {};
    for (const [k, v] of Object.entries(r)) {
      const s = sanitizeValue(v, o);
      if (s !== v) changedByColumn[k] = (changedByColumn[k] ?? 0) + 1;
      clean[k] = s;
    }
    return clean;
  });
  return { rows: out, changedByColumn };
}
