/**
 * Conversões e formatação pt-BR compartilhadas pelo interpretador de expressões e pelas
 * transformações. Datas são tratadas como "data civil" em UTC (sem fuso): "2026-10-18" é sempre
 * dia 18, em qualquer máquina, e nunca vira dia 17 por causa de fuso horário.
 */

const PT_NUMBER = /^-?\d{1,3}(\.\d{3})*(,\d+)?$|^-?\d+(,\d+)?$/;

/** Número a partir de number/boolean/string. Aceita "1250.50", "1.250,50" e "1250,5". */
export function toNumber(v: unknown): number {
  if (typeof v === 'number') return v;
  if (typeof v === 'boolean') return v ? 1 : 0;
  if (v instanceof Date) return v.getTime();
  if (typeof v !== 'string') return NaN;
  const s = v.trim().replace(/^R\$\s*/, '');
  if (s === '') return NaN;
  if (s.includes(',') && PT_NUMBER.test(s)) return Number(s.replace(/\./g, '').replace(',', '.'));
  return Number(s);
}

// Intl separa "R$" do número com espaço não separável (U+00A0); impressora e TSPL Latin-1
// lidam melhor com espaço comum, e a saída fica igual em qualquer ambiente.
const nbsp = / | /g;

// Criar um Intl.NumberFormat custa ~60 µs; num lote de 10.000 etiquetas isso pesa. Um por formato.
const formatters = new Map<string, Intl.NumberFormat>();
function formatter(key: string, make: () => Intl.NumberFormat): Intl.NumberFormat {
  let f = formatters.get(key);
  if (!f) formatters.set(key, (f = make()));
  return f;
}

export function formatNumber(n: number, decimals = 2): string {
  return formatter(`n${decimals}`, () => new Intl.NumberFormat('pt-BR', { minimumFractionDigits: decimals, maximumFractionDigits: decimals }))
    .format(n)
    .replace(nbsp, ' ');
}

export function formatCurrency(n: number, currency = 'BRL'): string {
  return formatter(`c${currency}`, () => new Intl.NumberFormat('pt-BR', { style: 'currency', currency }))
    .format(n)
    .replace(nbsp, ' ');
}

/** Data a partir de Date, "YYYY-MM-DD", ISO com hora ou "DD/MM/YYYY". Inválida = null. */
export function parseDate(v: unknown): Date | null {
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? null : v;
  if (typeof v !== 'string') return null;
  const s = v.trim();
  let m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(s);
  if (m) return utc(+m[1]!, +m[2]!, +m[3]!, +(m[4] ?? 0), +(m[5] ?? 0), +(m[6] ?? 0));
  m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(s);
  if (m) return utc(+m[3]!, +m[2]!, +m[1]!, 0, 0, 0);
  return null;
}

function utc(y: number, mo: number, d: number, h: number, mi: number, s: number): Date | null {
  const date = new Date(Date.UTC(y, mo - 1, d, h, mi, s));
  // Rejeita 31/02 e afins em vez de "rolar" para março.
  return date.getUTCMonth() === mo - 1 && date.getUTCDate() === d ? date : null;
}

/** Converte um instante local (ex.: `new Date()`) para a "data civil" usada aqui. */
export function civilFromLocal(d: Date): Date {
  return new Date(
    Date.UTC(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds()),
  );
}

const pad2 = (n: number) => String(n).padStart(2, '0');

/** Tokens: YYYY, YY, MM, DD, HH, mm, ss. */
export function formatDate(d: Date, pattern = 'DD/MM/YYYY'): string {
  return pattern.replace(/YYYY|YY|MM|DD|HH|mm|ss/g, (t) => {
    switch (t) {
      case 'YYYY':
        return String(d.getUTCFullYear());
      case 'YY':
        return String(d.getUTCFullYear()).slice(-2);
      case 'MM':
        return pad2(d.getUTCMonth() + 1);
      case 'DD':
        return pad2(d.getUTCDate());
      case 'HH':
        return pad2(d.getUTCHours());
      case 'mm':
        return pad2(d.getUTCMinutes());
      default:
        return pad2(d.getUTCSeconds());
    }
  });
}

export function addDays(d: Date, days: number): Date {
  return new Date(d.getTime() + days * 86_400_000);
}

/** Texto para exibir na etiqueta. null/undefined viram vazio, nunca "null". */
export function toText(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (v instanceof Date) return formatDate(v);
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : formatNumber(v, 2);
  return String(v);
}

export function isBlank(v: unknown): boolean {
  return v === null || v === undefined || (typeof v === 'string' && v.trim() === '');
}
