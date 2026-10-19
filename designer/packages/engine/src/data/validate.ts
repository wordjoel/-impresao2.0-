import type { BrandTheme } from '../model/brand';
import type { LabelTemplate } from '../model/objects';
import type { TextMeasurer } from '../layout/metrics';
import { checkGeometry, resolveLabel, type IssueCode, type IssueSeverity } from '../render/resolve';
import type { DataRow } from './sources';

/**
 * Data Validation Preview ("Sanity Check"): antes de um lote grande, roda o pipeline completo
 * (template -> dados -> layout -> código de barras) numa amostra e agrega o que daria errado.
 */

export interface ValidationFinding {
  severity: IssueSeverity;
  code: IssueCode;
  message: string;
  objectId?: string;
  /** Quantas linhas da amostra tiveram este problema. */
  count: number;
  /** Até 5 linhas de exemplo (base 1, como o operador conta no CSV). */
  exampleRows: number[];
}

export interface ValidationReport {
  totalRows: number;
  sampledRows: number;
  errors: number;
  warnings: number;
  findings: ValidationFinding[];
  /** Nenhum erro: pode imprimir (alertas não bloqueiam). */
  ok: boolean;
}

export interface ValidateOptions {
  /** Fração da base a amostrar (padrão 10%). */
  samplePercent?: number;
  /** Mínimo de linhas amostradas, quando houver (padrão 50). */
  minSample?: number;
  dpi?: number;
  measurer?: TextMeasurer;
  brand?: BrandTheme;
  now?: Date;
}

/**
 * Amostra determinística e espalhada: primeira, última e passo uniforme entre elas.
 * Reproduzível (mesmo relatório a cada clique) e cobre começo, meio e fim do arquivo.
 */
export function sampleIndices(total: number, percent = 10, min = 50): number[] {
  if (total <= 0) return [];
  const n = Math.min(total, Math.max(min, Math.ceil((total * percent) / 100)));
  if (n >= total) return Array.from({ length: total }, (_, i) => i);
  const out = new Set<number>([0, total - 1]);
  const step = (total - 1) / (n - 1);
  for (let k = 1; out.size < n && k < n - 1; k++) out.add(Math.round(k * step));
  return [...out].sort((a, b) => a - b);
}

export function validateBatch(
  template: LabelTemplate,
  rows: readonly DataRow[],
  opts: ValidateOptions = {},
): ValidationReport {
  const indices = sampleIndices(rows.length, opts.samplePercent, opts.minSample);
  const byKey = new Map<string, ValidationFinding>();
  const add = (f: Omit<ValidationFinding, 'count' | 'exampleRows'>, row: number | null) => {
    // Agrupa pela causa, não pela mensagem (a mensagem traz o valor, que muda por linha).
    const key = `${f.code}|${f.objectId ?? ''}|${f.code === 'MISSING_FIELD' ? f.message : ''}`;
    const cur = byKey.get(key) ?? { ...f, count: 0, exampleRows: [] };
    cur.count++;
    if (row !== null && cur.exampleRows.length < 5) cur.exampleRows.push(row + 1);
    byKey.set(key, cur);
  };

  checkGeometry(template, opts.brand).forEach((i) => add(i, null));
  if (rows.length === 0) {
    add({ severity: 'error', code: 'MISSING_FIELD', message: 'Nenhuma linha de dados' }, null);
  }
  for (const idx of indices) {
    const label = resolveLabel(template, rows[idx]!, {
      dpi: opts.dpi,
      measurer: opts.measurer,
      brand: opts.brand,
      index: idx,
      now: opts.now,
      checkGeometry: false,
    });
    label.issues.forEach((i) => add(i, idx));
  }

  const findings = [...byKey.values()].sort(
    (a, b) => (a.severity === b.severity ? b.count - a.count : a.severity === 'error' ? -1 : 1),
  );
  const errors = findings.filter((f) => f.severity === 'error').reduce((s, f) => s + f.count, 0);
  const warnings = findings.filter((f) => f.severity === 'warning').reduce((s, f) => s + f.count, 0);
  return { totalRows: rows.length, sampledRows: indices.length, errors, warnings, findings, ok: errors === 0 };
}
