import {
  ExpressionError,
  Parser,
  collectFields,
  evaluate,
  isVolatile,
  tokenize,
  type EvalContext,
  type Node,
} from '../expr/expression';
import { toText } from './format';
import { applyTransforms, TransformError, type TransformStep } from './transforms';

/**
 * String de template: texto literal com marcadores `{{ expressão | pipe:arg | pipe }}`.
 * Compilada uma vez por template (cache), avaliada por linha de dados.
 */

export type TemplatePart =
  | { kind: 'text'; text: string }
  | { kind: 'expr'; source: string; node: Node; pipes: TransformStep[] };

export interface CompiledString {
  source: string;
  parts: TemplatePart[];
  /** Sem marcador nenhum: o resultado é o próprio texto. */
  isStatic: boolean;
  /** Depende da posição no lote ou do relógio (SEQ, TODAY, $index). */
  isVolatile: boolean;
  fields: Set<string>;
}

export class TemplateError extends Error {
  constructor(
    message: string,
    readonly source: string,
  ) {
    super(message);
    this.name = 'TemplateError';
  }
}

/** Divide em `sep` fora de aspas (as aspas ficam no pedaço; ver `unquote`). */
function splitRaw(s: string, sep: string): string[] {
  const out: string[] = [];
  let cur = '';
  let quote: string | null = null;
  for (const c of s) {
    if (quote) {
      if (c === quote) quote = null;
      cur += c;
    } else if (c === '"' || c === "'") {
      quote = c;
      cur += c;
    } else if (c === sep) {
      out.push(cur);
      cur = '';
    } else cur += c;
  }
  out.push(cur);
  return out;
}

/** Posição do primeiro "|" sozinho (não "||") fora de aspas e colchetes, ou -1. */
function firstPipe(body: string): number {
  let quote: string | null = null;
  let bracket = false;
  for (let i = 0; i < body.length; i++) {
    const c = body[i]!;
    if (quote) {
      if (c === quote) quote = null;
    } else if (bracket) {
      if (c === ']') bracket = false;
    } else if (c === '"' || c === "'") quote = c;
    else if (c === '[') bracket = true;
    else if (c === '|') {
      if (body[i + 1] === '|') i++;
      else return i;
    }
  }
  return -1;
}

function parsePlaceholder(body: string): { node: Node; pipes: TransformStep[] } {
  const cut = firstPipe(body);
  const exprSource = cut < 0 ? body : body.slice(0, cut);
  const p = new Parser(tokenize(exprSource));
  const node = p.parseExpression();
  const rest = p.peek();
  if (rest.type !== 'eof') throw new ExpressionError(`Inesperado "${rest.value}"`, rest.pos);
  if (cut < 0) return { node, pipes: [] };

  // Pipes são lidos como texto cru: "date:DD/MM/YY" e "default:SEM LOTE" não são expressões.
  const pipes = splitRaw(body.slice(cut + 1), '|').map((seg) => {
    const [name, ...args] = splitRaw(seg, ':');
    const n = name!.trim();
    if (!/^[A-Za-z]+$/.test(n)) throw new ExpressionError(`Transformação inválida "${seg.trim()}"`, cut);
    // Entre aspas o argumento é literal ("default:' '" preserva o espaço); sem aspas, é aparado.
    const unquote = (a: string) => {
      const t = a.trim();
      return /^(["']).*\1$/s.test(t) ? t.slice(1, -1) : t;
    };
    return { name: n, args: args.map(unquote) };
  });
  return { node, pipes };
}

const cache = new Map<string, CompiledString>();

export function compileString(source: string): CompiledString {
  const hit = cache.get(source);
  if (hit) return hit;

  const parts: TemplatePart[] = [];
  let i = 0;
  while (i < source.length) {
    const open = source.indexOf('{{', i);
    if (open < 0) {
      parts.push({ kind: 'text', text: source.slice(i) });
      break;
    }
    if (open > i) parts.push({ kind: 'text', text: source.slice(i, open) });
    const close = source.indexOf('}}', open + 2);
    if (close < 0) throw new TemplateError('Marcador {{ sem }} de fechamento', source);
    const body = source.slice(open + 2, close);
    try {
      parts.push({ kind: 'expr', source: body.trim(), ...parsePlaceholder(body) });
    } catch (e) {
      throw new TemplateError(`{{${body}}}: ${(e as Error).message}`, source);
    }
    i = close + 2;
  }

  const exprs = parts.filter((p): p is Extract<TemplatePart, { kind: 'expr' }> => p.kind === 'expr');
  const fields = new Set<string>();
  exprs.forEach((e) => collectFields(e.node, fields));
  const compiled: CompiledString = {
    source,
    parts,
    isStatic: exprs.length === 0,
    isVolatile: exprs.some((e) => isVolatile(e.node)),
    fields,
  };
  if (cache.size > 5000) cache.clear();
  cache.set(source, compiled);
  return compiled;
}

export function renderString(compiled: CompiledString, ctx: EvalContext): string {
  if (compiled.isStatic) return compiled.parts.map((p) => (p.kind === 'text' ? p.text : '')).join('');
  let out = '';
  for (const p of compiled.parts) {
    if (p.kind === 'text') {
      out += p.text;
      continue;
    }
    try {
      out += toText(applyTransforms(evaluate(p.node, ctx), p.pipes));
    } catch (e) {
      if (e instanceof ExpressionError || e instanceof TransformError) {
        throw new TemplateError(`{{${p.source}}}: ${e.message}`, compiled.source);
      }
      throw e;
    }
  }
  return out;
}

/** Atalho para quem não precisa do cache explícito. */
export function renderTemplate(source: string, ctx: EvalContext): string {
  return renderString(compileString(source), ctx);
}
