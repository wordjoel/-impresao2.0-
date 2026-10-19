/**
 * Abstração de fonte de dados (Adapter). Seja CSV, JSON, API REST ou dados fictícios, tudo vira
 * `DataSet`: cabeçalhos + linhas `Record<string, string>`. O layout nunca sabe de onde o dado veio.
 */

export type DataRow = Record<string, string>;

export interface DataSet {
  name: string;
  kind: 'csv' | 'json' | 'rest' | 'mock';
  headers: string[];
  rows: DataRow[];
}

export class DataSourceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DataSourceError';
  }
}

/** Separador mais frequente na primeira linha, fora de aspas: ; , tab ou |. */
export function detectDelimiter(text: string): string {
  const firstLine = text.split(/\r?\n/, 1)[0] ?? '';
  let best = ',';
  let bestCount = 0;
  for (const d of [';', ',', '\t', '|']) {
    let count = 0;
    let quoted = false;
    for (const c of firstLine) {
      if (c === '"') quoted = !quoted;
      else if (c === d && !quoted) count++;
    }
    if (count > bestCount) {
      best = d;
      bestCount = count;
    }
  }
  return best;
}

/**
 * CSV no padrão RFC 4180 (aspas, aspas duplicadas, quebra de linha dentro de aspas), com BOM do
 * Excel removido e separador detectado. Cabeçalho vazio ou repetido ganha nome único.
 */
export function parseCsv(text: string, name = 'dados.csv', delimiter = detectDelimiter(text)): DataSet {
  const src = text.replace(/^﻿/, '');
  const records: string[][] = [];
  let field = '';
  let record: string[] = [];
  let quoted = false;

  for (let i = 0; i < src.length; i++) {
    const c = src[i]!;
    if (quoted) {
      if (c === '"' && src[i + 1] === '"') {
        field += '"';
        i++;
      } else if (c === '"') quoted = false;
      else field += c;
      continue;
    }
    if (c === '"' && field === '') quoted = true;
    else if (c === delimiter) {
      record.push(field);
      field = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && src[i + 1] === '\n') i++;
      record.push(field);
      records.push(record);
      record = [];
      field = '';
    } else field += c;
  }
  if (quoted) throw new DataSourceError('CSV com aspas sem fechamento');
  if (field !== '' || record.length > 0) {
    record.push(field);
    records.push(record);
  }

  const nonEmpty = records.filter((r) => r.some((v) => v.trim() !== ''));
  if (nonEmpty.length === 0) throw new DataSourceError('CSV vazio');
  const headers = uniqueHeaders(nonEmpty[0]!);
  const rows = nonEmpty.slice(1).map((r) => {
    const row: DataRow = {};
    headers.forEach((h, i) => (row[h] = r[i] ?? ''));
    return row;
  });
  return { name, kind: 'csv', headers, rows };
}

function uniqueHeaders(raw: string[]): string[] {
  const seen = new Map<string, number>();
  return raw.map((h, i) => {
    const base = h.trim() || `COLUNA_${i + 1}`;
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    return n === 0 ? base : `${base}_${n + 1}`;
  });
}

/** JSON: array de objetos, ou objeto com array em `data`/`items`/`rows`/`results`. */
export function parseJsonRecords(text: string | unknown, name = 'dados.json', kind: DataSet['kind'] = 'json'): DataSet {
  let parsed: unknown;
  try {
    parsed = typeof text === 'string' ? JSON.parse(text) : text;
  } catch (e) {
    throw new DataSourceError(`JSON inválido: ${(e as Error).message}`);
  }
  const arr = Array.isArray(parsed)
    ? parsed
    : ['data', 'items', 'rows', 'results'].map((k) => (parsed as Record<string, unknown>)?.[k]).find(Array.isArray);
  if (!Array.isArray(arr)) throw new DataSourceError('JSON precisa ser uma lista de objetos (ou ter "data"/"items")');

  const headers: string[] = [];
  const rows = arr.map((item, i) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      throw new DataSourceError(`Item ${i + 1} não é um objeto`);
    }
    const row: DataRow = {};
    for (const [k, v] of Object.entries(item as Record<string, unknown>)) {
      if (!headers.includes(k)) headers.push(k);
      row[k] = v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
    }
    return row;
  });
  rows.forEach((r) => headers.forEach((h) => (r[h] ??= '')));
  return { name, kind, headers, rows };
}

/**
 * Mapeamento de colunas: `mapping[campoDoTemplate] = colunaDaFonte`. Campos sem mapeamento
 * continuam disponíveis pelo nome original da coluna.
 */
export function applyMapping(row: DataRow, mapping: Readonly<Record<string, string>>): DataRow {
  const out: DataRow = { ...row };
  for (const [field, column] of Object.entries(mapping)) {
    if (column && Object.prototype.hasOwnProperty.call(row, column)) out[field] = row[column]!;
  }
  return out;
}

/**
 * Sugere o mapeamento por nome parecido (sem acento, sem caixa, sem espaço/underscore):
 * campo "PRECO" encontra coluna "Preço", "SKU" encontra "sku".
 */
export function suggestMapping(fields: Iterable<string>, headers: readonly string[]): Record<string, string> {
  const norm = (s: string) =>
    s
      .normalize('NFD')
      .replace(/\p{M}/gu, '')
      .toLowerCase()
      .replace(/[\s_\-.]/g, '');
  const out: Record<string, string> = {};
  for (const f of fields) {
    const hit = headers.find((h) => norm(h) === norm(f));
    if (hit) out[f] = hit;
  }
  return out;
}
