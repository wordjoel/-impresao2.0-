import { addDays, civilFromLocal, formatCurrency, formatDate, formatNumber, isBlank, parseDate, toNumber, toText } from '../data/format';

/**
 * Mini-interpretador de expressões das etiquetas. Nada de `eval`/`Function`: tokenizador +
 * parser de precedência + avaliador sobre AST, com limites de tamanho e profundidade.
 *
 *   PRECO * QTD                       aritmética (+ é só numérico; & concatena texto)
 *   IF(QTD > 10, "GRÁTIS", "PAGO")    funções no estilo planilha (nomes sem diferenciar caixa)
 *   [Nome do Produto]                 campo com espaço
 *   QTD > 10 ? "A" : "B"              ternário
 *   DATEFMT(DATEADD(TODAY(), 30))     validade = hoje + 30 dias
 *   SEQ(1, 1, 6)                      auto-incremento: 000001, 000002...
 */

export type Value = number | string | boolean | Date | null;

export class ExpressionError extends Error {
  constructor(
    message: string,
    readonly position: number,
  ) {
    super(message);
    this.name = 'ExpressionError';
  }
}

export interface EvalContext {
  row: Readonly<Record<string, unknown>>;
  /** Índice global da etiqueta no lote (0 = primeira). Base do SEQ. */
  index: number;
  /** Relógio injetável (testes e reimpressão determinística). */
  now: Date;
  /** Campos referenciados que não existem na linha (usado pela validação). */
  missing?: Set<string>;
}

// ---------------------------------------------------------------- tokens

type TokType = 'num' | 'str' | 'ident' | 'op' | 'eof';
export interface Token {
  type: TokType;
  value: string;
  pos: number;
}

const MAX_SOURCE = 2000;
const MAX_DEPTH = 64;

const OPS = ['&&', '||', '==', '!=', '<>', '<=', '>=', '+', '-', '*', '/', '%', '&', '(', ')', ',', '<', '>', '=', '!', '?', ':', '|'];

export function tokenize(src: string): Token[] {
  if (src.length > MAX_SOURCE) throw new ExpressionError(`Expressão longa demais (máx. ${MAX_SOURCE})`, 0);
  const out: Token[] = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i]!;
    if (/\s/.test(c)) {
      i++;
      continue;
    }
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(src[i + 1] ?? ''))) {
      const m = /^\d*\.?\d+(e[+-]?\d+)?/i.exec(src.slice(i))!;
      out.push({ type: 'num', value: m[0], pos: i });
      i += m[0].length;
      continue;
    }
    if (c === '"' || c === "'") {
      let j = i + 1;
      let s = '';
      while (j < src.length && src[j] !== c) {
        if (src[j] === '\\' && j + 1 < src.length) j++;
        s += src[j];
        j++;
      }
      if (j >= src.length) throw new ExpressionError('Texto sem aspas de fechamento', i);
      out.push({ type: 'str', value: s, pos: i });
      i = j + 1;
      continue;
    }
    if (c === '[') {
      const j = src.indexOf(']', i);
      if (j < 0) throw new ExpressionError('Campo sem ] de fechamento', i);
      out.push({ type: 'ident', value: src.slice(i + 1, j).trim(), pos: i });
      i = j + 1;
      continue;
    }
    const id = /^[\p{L}_$][\p{L}\p{N}_.$]*/u.exec(src.slice(i));
    if (id) {
      out.push({ type: 'ident', value: id[0], pos: i });
      i += id[0].length;
      continue;
    }
    const op = OPS.find((o) => src.startsWith(o, i));
    if (!op) throw new ExpressionError(`Caractere inesperado "${c}"`, i);
    out.push({ type: 'op', value: op, pos: i });
    i += op.length;
  }
  out.push({ type: 'eof', value: '', pos: src.length });
  return out;
}

// ---------------------------------------------------------------- AST

export type Node =
  | { k: 'lit'; v: Value }
  | { k: 'field'; name: string; pos: number }
  | { k: 'un'; op: string; a: Node }
  | { k: 'bin'; op: string; a: Node; b: Node; pos: number }
  | { k: 'cond'; c: Node; a: Node; b: Node }
  | { k: 'call'; name: string; args: Node[]; pos: number };

const KEYWORD_OPS: Record<string, string> = { AND: '&&', OR: '||', NOT: '!' };

/** Parser de precedência. `stopAtPipe` deixa o "|" sobrar para o parser de template (pipes). */
export class Parser {
  private i = 0;
  private depth = 0;

  constructor(private readonly toks: Token[]) {}

  get position(): number {
    return this.i;
  }

  peek(): Token {
    return this.toks[this.i]!;
  }

  next(): Token {
    return this.toks[this.i++]!;
  }

  private isOp(v: string): boolean {
    const t = this.peek();
    if (t.type === 'op' && t.value === v) return true;
    return t.type === 'ident' && KEYWORD_OPS[t.value.toUpperCase()] === v && this.toks[this.i + 1]?.value !== '(';
  }

  expect(v: string): void {
    if (!this.isOp(v)) throw new ExpressionError(`Esperado "${v}"`, this.peek().pos);
    this.i++;
  }

  parseExpression(): Node {
    if (++this.depth > MAX_DEPTH) throw new ExpressionError('Expressão aninhada demais', this.peek().pos);
    const n = this.ternary();
    this.depth--;
    return n;
  }

  private ternary(): Node {
    const c = this.binary(0);
    if (!this.isOp('?')) return c;
    this.i++;
    const a = this.parseExpression();
    this.expect(':');
    const b = this.parseExpression();
    return { k: 'cond', c, a, b };
  }

  private static readonly LEVELS: string[][] = [
    ['||'],
    ['&&'],
    ['==', '!=', '<>', '='],
    ['<', '<=', '>', '>='],
    ['&'],
    ['+', '-'],
    ['*', '/', '%'],
  ];

  private binary(level: number): Node {
    if (level >= Parser.LEVELS.length) return this.unary();
    let a = this.binary(level + 1);
    for (;;) {
      const op = Parser.LEVELS[level]!.find((o) => this.isOp(o));
      if (!op) return a;
      const pos = this.next().pos;
      a = { k: 'bin', op, a, b: this.binary(level + 1), pos };
    }
  }

  private unary(): Node {
    if (this.isOp('-') || this.isOp('!')) {
      const op = this.next().value;
      return { k: 'un', op: KEYWORD_OPS[op.toUpperCase()] ?? op, a: this.unary() };
    }
    return this.primary();
  }

  private primary(): Node {
    const t = this.next();
    if (t.type === 'num') return { k: 'lit', v: Number(t.value) };
    if (t.type === 'str') return { k: 'lit', v: t.value };
    if (t.type === 'op' && t.value === '(') {
      const n = this.parseExpression();
      this.expect(')');
      return n;
    }
    if (t.type === 'ident') {
      const upper = t.value.toUpperCase();
      if (this.isOp('(')) {
        this.i++;
        const args: Node[] = [];
        if (!this.isOp(')')) {
          do args.push(this.parseExpression());
          while (this.isOp(',') && this.next());
        }
        this.expect(')');
        return { k: 'call', name: upper, args, pos: t.pos };
      }
      if (upper === 'TRUE' || upper === 'VERDADEIRO') return { k: 'lit', v: true };
      if (upper === 'FALSE' || upper === 'FALSO') return { k: 'lit', v: false };
      if (upper === 'NULL') return { k: 'lit', v: null };
      return { k: 'field', name: t.value, pos: t.pos };
    }
    throw new ExpressionError(t.type === 'eof' ? 'Expressão incompleta' : `Inesperado "${t.value}"`, t.pos);
  }
}

export function parseExpression(src: string): Node {
  const p = new Parser(tokenize(src));
  const n = p.parseExpression();
  const rest = p.peek();
  if (rest.type !== 'eof') throw new ExpressionError(`Inesperado "${rest.value}"`, rest.pos);
  return n;
}

// ---------------------------------------------------------------- avaliação

/** Busca exata e, se não houver, sem diferenciar maiúsculas ("sku" encontra "SKU"). */
export function lookupField(row: Readonly<Record<string, unknown>>, name: string): { found: boolean; value: unknown } {
  if (Object.prototype.hasOwnProperty.call(row, name)) return { found: true, value: row[name] };
  const lower = name.toLowerCase();
  for (const k of Object.keys(row)) if (k.toLowerCase() === lower) return { found: true, value: row[k] };
  return { found: false, value: null };
}

function normalize(v: unknown): Value {
  if (v === undefined || v === null) return null;
  if (typeof v === 'number' || typeof v === 'string' || typeof v === 'boolean' || v instanceof Date) return v;
  return String(v);
}

function truthy(v: Value): boolean {
  if (typeof v === 'string') return v.trim() !== '' && v.toLowerCase() !== 'false' && v !== '0';
  return Boolean(v);
}

function num(v: Value, pos: number, what: string): number {
  const n = toNumber(v);
  if (Number.isNaN(n)) throw new ExpressionError(`${what}: "${toText(v)}" não é número`, pos);
  return n;
}

function compare(a: Value, b: Value): number {
  const na = toNumber(a);
  const nb = toNumber(b);
  if (!Number.isNaN(na) && !Number.isNaN(nb)) return na - nb;
  return toText(a).localeCompare(toText(b), 'pt-BR');
}

function evalNode(n: Node, ctx: EvalContext): Value {
  switch (n.k) {
    case 'lit':
      return n.v;
    case 'field': {
      if (n.name === '$index') return ctx.index;
      if (n.name === '$row') return ctx.index + 1;
      const r = lookupField(ctx.row, n.name);
      if (!r.found) ctx.missing?.add(n.name);
      return normalize(r.value);
    }
    case 'un': {
      const a = evalNode(n.a, ctx);
      return n.op === '-' ? -num(a, 0, 'Sinal') : !truthy(a);
    }
    case 'cond':
      return truthy(evalNode(n.c, ctx)) ? evalNode(n.a, ctx) : evalNode(n.b, ctx);
    case 'bin': {
      if (n.op === '&&') return truthy(evalNode(n.a, ctx)) && truthy(evalNode(n.b, ctx));
      if (n.op === '||') return truthy(evalNode(n.a, ctx)) || truthy(evalNode(n.b, ctx));
      const a = evalNode(n.a, ctx);
      const b = evalNode(n.b, ctx);
      switch (n.op) {
        case '&':
          return toText(a) + toText(b);
        case '+':
          return num(a, n.pos, 'Soma') + num(b, n.pos, 'Soma');
        case '-':
          return num(a, n.pos, 'Subtração') - num(b, n.pos, 'Subtração');
        case '*':
          return num(a, n.pos, 'Multiplicação') * num(b, n.pos, 'Multiplicação');
        case '/': {
          const d = num(b, n.pos, 'Divisão');
          if (d === 0) throw new ExpressionError('Divisão por zero', n.pos);
          return num(a, n.pos, 'Divisão') / d;
        }
        case '%':
          return num(a, n.pos, 'Resto') % num(b, n.pos, 'Resto');
        case '==':
        case '=':
          return compare(a, b) === 0;
        case '!=':
        case '<>':
          return compare(a, b) !== 0;
        case '<':
          return compare(a, b) < 0;
        case '<=':
          return compare(a, b) <= 0;
        case '>':
          return compare(a, b) > 0;
        default:
          return compare(a, b) >= 0;
      }
    }
    case 'call':
      return callFunction(n, ctx);
  }
}

function callFunction(n: Extract<Node, { k: 'call' }>, ctx: EvalContext): Value {
  const lazy = n.args;
  // IF/COALESCE avaliam só o ramo necessário (um ramo com divisão por zero não deve explodir).
  if (n.name === 'IF' || n.name === 'SE') {
    if (lazy.length < 2) throw new ExpressionError('IF(condição, se_verdadeiro, se_falso)', n.pos);
    return truthy(evalNode(lazy[0]!, ctx)) ? evalNode(lazy[1]!, ctx) : lazy[2] ? evalNode(lazy[2], ctx) : null;
  }
  if (n.name === 'COALESCE') {
    for (const a of lazy) {
      const v = evalNode(a, ctx);
      if (!isBlank(v)) return v;
    }
    return null;
  }

  const args = lazy.map((a) => evalNode(a, ctx));
  const s = (i: number) => toText(args[i] ?? null);
  const nArg = (i: number, def?: number) =>
    args[i] === undefined && def !== undefined ? def : num(args[i] ?? null, n.pos, n.name);
  const date = (i: number) => {
    const d = parseDate(args[i] ?? null);
    if (!d) throw new ExpressionError(`${n.name}: "${s(i)}" não é data`, n.pos);
    return d;
  };

  switch (n.name) {
    case 'AND':
      return args.every(truthy);
    case 'OR':
      return args.some(truthy);
    case 'NOT':
      return !truthy(args[0] ?? null);
    case 'CONCAT':
      return args.map(toText).join('');
    case 'UPPER':
      return s(0).toLocaleUpperCase('pt-BR');
    case 'LOWER':
      return s(0).toLocaleLowerCase('pt-BR');
    case 'PROPER':
      return s(0).toLocaleLowerCase('pt-BR').replace(/(^|\s)(\p{L})/gu, (_, sp: string, l: string) => sp + l.toLocaleUpperCase('pt-BR'));
    case 'TRIM':
      return s(0).trim().replace(/\s+/g, ' ');
    case 'LEFT':
      return s(0).slice(0, nArg(1, 1));
    case 'RIGHT': {
      const k = nArg(1, 1);
      return k <= 0 ? '' : s(0).slice(-k);
    }
    case 'MID':
      return s(0).substr(nArg(1) - 1, nArg(2));
    case 'LEN':
      return s(0).length;
    case 'REPLACE':
      return s(0).split(s(1)).join(s(2));
    case 'PAD':
      return s(0).padStart(nArg(1), args[2] === undefined ? '0' : s(2));
    case 'ROUND': {
      const f = 10 ** nArg(1, 0);
      return Math.round(nArg(0) * f) / f;
    }
    case 'FLOOR':
      return Math.floor(nArg(0));
    case 'CEIL':
      return Math.ceil(nArg(0));
    case 'ABS':
      return Math.abs(nArg(0));
    case 'MIN':
      return Math.min(...args.map((_, i) => nArg(i)));
    case 'MAX':
      return Math.max(...args.map((_, i) => nArg(i)));
    case 'ISBLANK':
      return isBlank(args[0] ?? null);
    case 'NUMBER':
      return nArg(0);
    case 'TEXT':
      return formatNumber(nArg(0), nArg(1, 2));
    case 'CURRENCY':
      return formatCurrency(nArg(0));
    case 'SEQ': {
      // Auto-incremento: início + passo × posição no lote, com zeros à esquerda.
      const v = nArg(0, 1) + nArg(1, 1) * ctx.index;
      return String(v).padStart(nArg(2, 0), '0');
    }
    case 'TODAY': {
      const c = civilFromLocal(ctx.now);
      return new Date(Date.UTC(c.getUTCFullYear(), c.getUTCMonth(), c.getUTCDate()));
    }
    case 'NOW':
      return civilFromLocal(ctx.now);
    case 'DATEADD':
      return addDays(date(0), nArg(1));
    case 'DATEFMT':
      return formatDate(date(0), args[1] === undefined ? 'DD/MM/YYYY' : s(1));
    case 'EAN13':
      return ean13Complete(s(0), n.pos);
    default:
      throw new ExpressionError(`Função desconhecida ${n.name}()`, n.pos);
  }
}

function ean13Complete(v: string, pos: number): string {
  const d = v.replace(/\D/g, '');
  if (d.length !== 12) throw new ExpressionError('EAN13() espera 12 dígitos', pos);
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(d[i]) * (i % 2 === 0 ? 1 : 3);
  return d + String((10 - (sum % 10)) % 10);
}

export function evaluate(n: Node, ctx: EvalContext): Value {
  return evalNode(n, ctx);
}

/** Campos referenciados pela expressão (para mapeamento de colunas e validação). */
export function collectFields(n: Node, out = new Set<string>()): Set<string> {
  switch (n.k) {
    case 'field':
      if (!n.name.startsWith('$')) out.add(n.name);
      break;
    case 'un':
      collectFields(n.a, out);
      break;
    case 'bin':
      collectFields(n.a, out);
      collectFields(n.b, out);
      break;
    case 'cond':
      collectFields(n.c, out);
      collectFields(n.a, out);
      collectFields(n.b, out);
      break;
    case 'call':
      n.args.forEach((a) => collectFields(a, out));
      break;
  }
  return out;
}

/** true se o resultado depende da posição no lote ou do relógio (não pode ser cacheado). */
export function isVolatile(n: Node): boolean {
  switch (n.k) {
    case 'field':
      return n.name.startsWith('$');
    case 'un':
      return isVolatile(n.a);
    case 'bin':
      return isVolatile(n.a) || isVolatile(n.b);
    case 'cond':
      return isVolatile(n.c) || isVolatile(n.a) || isVolatile(n.b);
    case 'call':
      return ['SEQ', 'TODAY', 'NOW'].includes(n.name) || n.args.some(isVolatile);
    default:
      return false;
  }
}
