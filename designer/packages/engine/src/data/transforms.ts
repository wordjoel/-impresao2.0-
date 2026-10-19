import { formatCurrency, formatDate, formatNumber, parseDate, toNumber, toText } from './format';

/**
 * Pipeline de transformações: funções puras `valor -> valor`, aplicadas em sequência.
 *   {{PRECO | currency}}               1250.5        -> "R$ 1.250,50"
 *   {{VALIDADE | date}}                "2026-10-18"  -> "18/10/2026"
 *   {{PRODUTO | trim | upper | truncate:20}}
 */

export interface TransformStep {
  name: string;
  args: string[];
}

export class TransformError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TransformError';
  }
}

type Transform = (v: unknown, args: string[]) => unknown;

const intArg = (args: string[], i: number, def: number) => {
  const n = args[i] === undefined ? def : Number(args[i]);
  if (!Number.isInteger(n)) throw new TransformError(`argumento ${i + 1} deve ser inteiro`);
  return n;
};

const requireNumber = (v: unknown, name: string) => {
  const n = toNumber(v);
  if (Number.isNaN(n)) throw new TransformError(`${name}: "${toText(v)}" não é número`);
  return n;
};

export const TRANSFORMS: Record<string, { help: string; fn: Transform }> = {
  trim: { help: 'remove espaços nas pontas e repetidos', fn: (v) => toText(v).trim().replace(/\s+/g, ' ') },
  upper: { help: 'MAIÚSCULAS', fn: (v) => toText(v).toLocaleUpperCase('pt-BR') },
  lower: { help: 'minúsculas', fn: (v) => toText(v).toLocaleLowerCase('pt-BR') },
  title: {
    help: 'Primeira Letra Maiúscula',
    fn: (v) =>
      toText(v)
        .toLocaleLowerCase('pt-BR')
        .replace(/(^|\s)(\p{L})/gu, (_, s: string, l: string) => s + l.toLocaleUpperCase('pt-BR')),
  },
  currency: { help: 'moeda pt-BR (currency:USD para outra)', fn: (v, a) => formatCurrency(requireNumber(v, 'currency'), a[0] ?? 'BRL') },
  number: { help: 'número pt-BR (number:3 = 3 casas)', fn: (v, a) => formatNumber(requireNumber(v, 'number'), intArg(a, 0, 2)) },
  date: {
    help: 'data (date:DD/MM/YY, padrão DD/MM/YYYY)',
    fn: (v, a) => {
      const d = parseDate(v);
      if (!d) throw new TransformError(`date: "${toText(v)}" não é data`);
      return formatDate(d, a[0] ?? 'DD/MM/YYYY');
    },
  },
  truncate: {
    help: 'corta em N caracteres (truncate:20:… para reticências)',
    fn: (v, a) => {
      const s = toText(v);
      const n = intArg(a, 0, 20);
      const tail = a[1] ?? '';
      return s.length <= n ? s : s.slice(0, Math.max(0, n - tail.length)) + tail;
    },
  },
  pad: { help: 'zeros à esquerda (pad:6 ou pad:6:x)', fn: (v, a) => toText(v).padStart(intArg(a, 0, 6), a[1] ?? '0') },
  digits: { help: 'só dígitos', fn: (v) => toText(v).replace(/\D/g, '') },
  default: { help: 'valor se vazio (default:SEM LOTE)', fn: (v, a) => (toText(v).trim() === '' ? a[0] ?? '' : v) },
  replace: { help: 'troca texto (replace:de:para)', fn: (v, a) => toText(v).split(a[0] ?? '').join(a[1] ?? '') },
  prefix: { help: 'texto antes, se não vazio', fn: (v, a) => (toText(v) === '' ? '' : (a[0] ?? '') + toText(v)) },
  suffix: { help: 'texto depois, se não vazio', fn: (v, a) => (toText(v) === '' ? '' : toText(v) + (a[0] ?? '')) },
};

export function applyTransforms(value: unknown, steps: readonly TransformStep[]): unknown {
  let v = value;
  for (const step of steps) {
    const t = TRANSFORMS[step.name.toLowerCase()];
    if (!t) throw new TransformError(`transformação desconhecida "${step.name}"`);
    v = t.fn(v, step.args);
  }
  return v;
}
