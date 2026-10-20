import {
  afmMeasurer,
  BarcodeError,
  canonicalJson,
  compileString,
  contentOf,
  dotsToMm,
  ean13CheckDigit,
  gs1Check,
  mmToDots,
  moduleDots,
  normalize1D,
  resolveLabel,
  sha256Hex,
  sortedByZ,
  symbolSize2D,
  upcaCheckDigit,
  type LabelObject,
  type LabelTemplate,
  type Node,
  type ResolvedElement,
  type TemplatePart,
} from '@athos-label/engine';
import { latin1Bytes, quarterTurn, toCp1252 } from './common';
import { GENERATORS } from './compile';
import type { ElementContext } from './printer';
import { zplEscape } from './zpl';

/**
 * Pacote para o coletor (passo 6, contrato docs/contratos/designer-coletor.openapi.yaml).
 *
 * O app Android não tem o motor do designer. Aqui o template vira comandos prontos (`lit`) com
 * lacunas (`slot`): o app só valida, escapa e concatena (`assembleLabel` é a referência desse
 * algoritmo, que o Kotlin repete). Os comandos saem dos MESMOS geradores ZPL/TSPL da impressão
 * pelo PC: cada objeto dinâmico é gerado com marcadores no lugar dos valores e cortado neles.
 *
 * v1 só leva o que tem geometria independente do valor (ver `PortabilityReason`). Os vetores de
 * conformidade são conferidos aqui mesmo: os bytes montados pelo pacote têm de bater com os do
 * gerador para o mesmo valor, senão o compilador recusa (nunca publica pacote que diverge do PC).
 */

export const COLETOR_FORMAT = 'athos-label-coletor';
export const COLETOR_FORMAT_VERSION = 1;
export const COLETOR_COMPILER = '@athos-label/exporters 0.1.0';
export const COLETOR_DPIS: readonly number[] = [203, 300, 600];
export const MAX_COPIES = 9999;

export type ColetorLanguage = 'ZPL' | 'TSPL';
export type SlotKind = 'TEXT' | 'EAN13' | 'EAN8' | 'UPCA' | 'CODE128' | 'QR';
export type PortabilityReason =
  | 'SEQUENCE'
  | 'DATE_FUNCTION'
  | 'EXPRESSION'
  | 'TEXT_FIT'
  | 'ROTATION'
  | 'DYNAMIC_IMAGE'
  | 'SYMBOLOGY'
  | 'CHARSET'
  | 'BARCODE_SIZE';

export interface PortabilityProblem {
  objectId: string;
  objectName: string;
  reason: PortabilityReason;
}

export interface TargetId {
  language: ColetorLanguage;
  dpi: number;
}

export interface PackageSlot {
  id: string;
  field: string;
  kind: SlotKind;
  transform: 'NONE' | 'GS1_DATA';
  maxLength: number;
}

export type PackageSegment = { lit: string } | { slot: string } | { copies: true };

export interface ConformanceVector {
  values: Record<string, string>;
  copies: number;
  bytesSha256: string;
}

export interface PackageTarget extends TargetId {
  segments: PackageSegment[];
  slots: PackageSlot[];
  conformance: ConformanceVector[];
}

export interface PackageField {
  name: string;
  label: string;
  required: boolean;
  maxLength: number;
  input: 'SCAN' | 'KEYBOARD' | 'ANY';
  sample?: string;
}

export interface LabelPackage {
  format: typeof COLETOR_FORMAT;
  formatVersion: typeof COLETOR_FORMAT_VERSION;
  templateId: string;
  name: string;
  templateVersion: number;
  templateHash: string;
  widthMm: number;
  heightMm: number;
  compiler: string;
  compiledAt: string;
  fields: PackageField[];
  targets: PackageTarget[];
}

export interface ColetorCompileInput {
  template: LabelTemplate;
  templateVersion: number;
  templateHash: string;
  targets: readonly TargetId[];
  /** Valores de exemplo (prévia do designer): prévia no app, módulo do Code 128/QR e 1º vetor. */
  samples?: Readonly<Record<string, string>>;
  now?: Date;
}

/** Pedido inválido (alvo repetido, DPI fora da lista, hash que não bate): 400 no servidor. */
export class PackageInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PackageInputError';
  }
}

/** Template que o v1 não leva ao coletor: 422 TEMPLATE_NOT_PORTABLE com a lista completa. */
export class NotPortableError extends Error {
  constructor(readonly problems: PortabilityProblem[]) {
    super(`${problems.length} objeto(s) não podem ir para o coletor`);
    this.name = 'NotPortableError';
  }
}

/** Valor recusado por uma lacuna. MISSING = MISSING_VARIABLES, INVALID = INVALID_VARIABLE_VALUE na ponte. */
export class SlotValueError extends Error {
  constructor(
    readonly field: string,
    readonly code: 'MISSING' | 'INVALID',
    message: string,
  ) {
    super(message);
    this.name = 'SlotValueError';
  }
}

// ------------------------------------------------------------------ montagem (referência do app)

// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/;
const LEADING_MARK = /^\p{M}/u;
const utf8Length = (s: string) => new TextEncoder().encode(s).length;
const codePoints = (s: string) => [...s].length;

/**
 * Valor de UM campo para UMA lacuna, já escapado para a linguagem. Ordem do contrato:
 * controle → validação/normalização do tipo → tamanho (recusa, nunca corta) → escape.
 */
export function fillSlot(slot: PackageSlot, raw: string | undefined, language: ColetorLanguage): string {
  const invalid = (why: string) => new SlotValueError(slot.field, 'INVALID', `${slot.field}: ${why}`);
  // v1: todo campo é obrigatório (linha vazia no gerador some do comando e mudaria a geometria).
  if (raw === undefined || raw === '') throw new SlotValueError(slot.field, 'MISSING', `${slot.field}: campo obrigatório vazio`);
  if (CONTROL.test(raw)) throw invalid('caractere de controle não é aceito');

  let value = raw;
  if (slot.kind !== 'TEXT' && slot.kind !== 'QR') {
    try {
      value = normalize1D(slot.kind, raw).printerData;
    } catch (e) {
      if (e instanceof BarcodeError) throw invalid(e.message);
      throw e;
    }
  }

  const length = slot.kind === 'QR' ? utf8Length(value) : codePoints(language === 'TSPL' ? value.normalize('NFC') : value);
  if (length > slot.maxLength) throw invalid(`passou do limite de ${slot.maxLength} (tem ${length})`);

  if (language === 'ZPL') return zplEscape(value);
  // TSPL normaliza o comando inteiro (NFC): acento solto no início se juntaria ao texto fixo.
  if (LEADING_MARK.test(value)) throw invalid('começa com acento solto');
  let lost: string | null = null;
  const cp = toCp1252(value, (ch) => (lost ??= ch));
  if (lost !== null) throw invalid(`caractere "${lost}" não existe na impressora (Windows-1252)`);
  return cp.replace(/"/g, '\\["]');
}

/** Bytes de UMA etiqueta para o socket: o que o app faz com o pacote. */
export function assembleLabel(target: PackageTarget, values: Readonly<Record<string, string>>, copies = 1): Uint8Array {
  if (!Number.isInteger(copies) || copies < 1 || copies > MAX_COPIES) throw new RangeError(`Cópias entre 1 e ${MAX_COPIES}`);
  const slots = new Map(target.slots.map((s) => [s.id, s]));
  let out = '';
  for (const seg of target.segments) {
    if ('lit' in seg) out += seg.lit;
    else if ('slot' in seg) {
      const slot = slots.get(seg.slot);
      if (!slot) throw new Error(`Pacote sem a lacuna ${seg.slot}`);
      out += fillSlot(slot, values[slot.field], target.language);
    } else out += String(copies);
  }
  return target.language === 'ZPL' ? new TextEncoder().encode(out) : latin1Bytes(out);
}

// ------------------------------------------------------------------ análise de portabilidade

const DATE_FUNCTIONS = new Set(['TODAY', 'NOW', 'DATEADD', 'DATEFMT']);

function exprReason(n: Node): PortabilityReason | null {
  if (n.k === 'field') return n.name.startsWith('$') ? 'SEQUENCE' : null;
  let seq = false;
  let date = false;
  const walk = (x: Node): void => {
    switch (x.k) {
      case 'field':
        if (x.name.startsWith('$')) seq = true;
        break;
      case 'un':
        walk(x.a);
        break;
      case 'bin':
        walk(x.a);
        walk(x.b);
        break;
      case 'cond':
        walk(x.c);
        walk(x.a);
        walk(x.b);
        break;
      case 'call':
        if (x.name === 'SEQ') seq = true;
        if (DATE_FUNCTIONS.has(x.name)) date = true;
        x.args.forEach(walk);
        break;
    }
  };
  walk(n);
  return seq ? 'SEQUENCE' : date ? 'DATE_FUNCTION' : 'EXPRESSION';
}

/** Pedaços do conteúdo: texto fixo ou nome de campo. */
type Piece = { text: string } | { field: string };

interface DynamicPlan {
  obj: LabelObject;
  pieces: Piece[];
  kind: SlotKind;
}

const NATIVE_1D = new Set(['EAN13', 'EAN8', 'UPCA', 'CODE128']);

function planObject(obj: LabelObject): DynamicPlan | PortabilityReason | null {
  const content = contentOf(obj);
  if (content === null) return null; // imagem e caixa: sempre estáticas no modelo
  let parts: TemplatePart[];
  try {
    const compiled = compileString(content);
    if (compiled.isStatic) return null;
    parts = compiled.parts;
  } catch {
    return 'EXPRESSION';
  }
  const pieces: Piece[] = [];
  for (const p of parts) {
    if (p.kind === 'text') {
      if (CONTROL.test(p.text)) return obj.type === 'TEXT' && p.text.includes('\n') ? 'TEXT_FIT' : 'CHARSET';
      pieces.push({ text: p.text });
      continue;
    }
    if (p.pipes.length > 0) return 'EXPRESSION';
    const reason = exprReason(p.node);
    if (reason) return reason;
    pieces.push({ field: (p.node as Extract<Node, { k: 'field' }>).name });
  }
  // Texto fixo começando com acento solto logo depois de um valor se juntaria a ele no NFC do TSPL.
  for (let i = 1; i < pieces.length; i++) {
    const p = pieces[i]!;
    if ('text' in p && 'field' in pieces[i - 1]! && LEADING_MARK.test(p.text)) return 'CHARSET';
  }

  const rot = quarterTurn(obj.rotation);
  if (!rot.exact) return 'ROTATION';
  const onlyField = pieces.length === 1 && 'field' in pieces[0]!;

  switch (obj.type) {
    case 'TEXT':
      return obj.fit === 'NONE' ? { obj, pieces, kind: 'TEXT' } : 'TEXT_FIT';
    case 'BARCODE_1D':
      if (!NATIVE_1D.has(obj.symbology)) return 'SYMBOLOGY';
      if (!onlyField) return 'EXPRESSION';
      // Code 128: a largura varia com o valor; girado 180°/270° a posição dependeria dela.
      if (obj.symbology === 'CODE128' && (rot.q === 2 || rot.q === 3)) return 'ROTATION';
      return { obj, pieces, kind: obj.symbology as SlotKind };
    case 'BARCODE_2D':
      return obj.symbology === 'QR' ? { obj, pieces, kind: 'QR' } : 'SYMBOLOGY';
    default:
      return 'DYNAMIC_IMAGE';
  }
}

// ------------------------------------------------------------------ valores de exemplo e de teste

const withCheck = {
  EAN13: (d: string) => d + ean13CheckDigit(d),
  EAN8: (d: string) => d + gs1Check(d),
  UPCA: (d: string) => d + upcaCheckDigit(d),
};

/** Valor padrão (1º vetor) e de estresse (2º vetor: acento e os escapes das duas linguagens). */
const DEFAULTS: Record<SlotKind, [string, string]> = {
  EAN13: [withCheck.EAN13('789123456789'), '200000000001'],
  EAN8: [withCheck.EAN8('1234567'), '7654321'],
  UPCA: [withCheck.UPCA('01234567890'), '03600029145'],
  CODE128: ['ABC-12345678', 'A"^~_\\b 9'],
  QR: ['ABC123', 'Ação "ç" ^~_\\ é'],
  TEXT: ['Lote 123', 'Ação "ç" ^~_\\ é'],
};

/** Campo usado por várias lacunas: o valor de teste segue a mais restrita. */
const KIND_PRIORITY: SlotKind[] = ['EAN13', 'UPCA', 'EAN8', 'CODE128', 'QR', 'TEXT'];

/** Pior caso do Code 128 (todo no conjunto B): início + 11 por caractere + verificador + parada. */
const code128WorstModules = (chars: number) => 35 + 11 * chars;

// Glifo mais largo do conjunto que a impressora representa (Windows-1252), para o limite conservador.
const MEASURE_CHARS = (() => {
  let s = '€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ';
  for (let c = 0x20; c <= 0x7e; c++) s += String.fromCharCode(c);
  for (let c = 0xa0; c <= 0xff; c++) s += String.fromCharCode(c);
  return [...s];
})();

// ------------------------------------------------------------------ compilação

interface Freeze {
  moduleDots?: number;
  symbolModules?: number;
}

const mark = (i: number) => `\u0001${i}\u0002`;
// eslint-disable-next-line no-control-regex
const MARK = /\u0001(\d+)\u0002/;

/** Rodapé do pacote. ZPL leva ^PQ sempre (o gerador omite ^PQ1; o efeito na impressora é o mesmo). */
function packageFooter(language: ColetorLanguage, copies: string): string {
  return language === 'ZPL' ? `^PQ${copies}\n^XZ\n` : `PRINT 1,${copies}\r\n`;
}

function applyFreeze(el: ResolvedElement, f: Freeze | undefined, dpi: number): ResolvedElement {
  if (!f) return el;
  if (el.kind === 'barcode1d' && f.moduleDots) {
    const m = f.moduleDots;
    return { ...el, moduleDots: m, moduleMm: dotsToMm(m, dpi), barsWidthMm: dotsToMm(m * el.moduleCount, dpi) };
  }
  if (el.kind === 'barcode2d' && f.moduleDots) {
    return { ...el, moduleDots: f.moduleDots, moduleMm: dotsToMm(f.moduleDots, dpi), symbolModules: f.symbolModules ?? el.symbolModules };
  }
  return el;
}

const lostCharset = (ctx: ElementContext) => ctx.issues.some((i) => i.code === 'UNSUPPORTED' && i.message.includes('1252'));

interface CompiledTarget {
  segments: PackageSegment[];
  slots: PackageSlot[];
  freezes: Map<string, Freeze>;
}

function compileTarget(
  template: LabelTemplate,
  visible: LabelObject[],
  plans: Map<string, DynamicPlan>,
  target: TargetId,
  sampleFor: (field: string, kind: SlotKind) => string,
  problems: PortabilityProblem[],
): CompiledTarget {
  const { language, dpi } = target;
  const gen = GENERATORS[language];
  const resolveOpts = { dpi, checkGeometry: false, barcodeDetail: 'size' as const };
  const problem = (o: LabelObject, reason: PortabilityReason) => problems.push({ objectId: o.id, objectName: o.name, reason });

  const statics = resolveLabel({ ...template, objects: visible.filter((o) => !plans.has(o.id)) }, {}, resolveOpts);
  const staticEls = new Map(statics.elements.map((el) => [el.id, el]));

  const segments: PackageSegment[] = [];
  const slots: PackageSlot[] = [];
  const freezes = new Map<string, Freeze>();
  const lit = (s: string) => {
    if (s === '') return;
    const last = segments[segments.length - 1];
    if (last && 'lit' in last) last.lit += s;
    else segments.push({ lit: s });
  };

  lit(gen.header(template.widthMm, template.heightMm, dpi));
  for (const o of visible) {
    const ctx: ElementContext = { dpi, issues: [] };
    const plan = plans.get(o.id);
    if (!plan) {
      const el = staticEls.get(o.id);
      const cmd = el ? gen.element(el, ctx) : '';
      if (lostCharset(ctx)) problem(o, 'CHARSET');
      lit(cmd);
      continue;
    }

    const fields = plan.pieces.filter((p): p is { field: string } => 'field' in p).map((p) => p.field);
    const row = Object.fromEntries(fields.map((f) => [f, sampleFor(f, plan.kind)]));
    const resolved = resolveLabel({ ...template, objects: [o] }, row, resolveOpts);
    const el = resolved.elements[0];
    if (!el || el.kind === 'error' || resolved.issues.some((i) => i.code === 'BARCODE_TOO_SMALL')) {
      problem(o, plan.kind === 'TEXT' ? 'TEXT_FIT' : 'BARCODE_SIZE');
      continue;
    }
    // Índice do marcador = posição da lacuna dentro do objeto (um campo pode aparecer duas vezes).
    let n = 0;
    const withMarks = plan.pieces.map((p) => ('text' in p ? p.text : mark(n++))).join('');
    const literal = plan.pieces.map((p) => ('text' in p ? p.text : '')).join('');
    let maxLength: number;
    let marked: ResolvedElement;

    if (el.kind === 'text') {
      const font = { family: el.family, sizeMm: o.type === 'TEXT' ? o.font.sizeMm : el.fontSizeMm, bold: el.bold };
      const widest = Math.max(...MEASURE_CHARS.map((ch) => afmMeasurer.width(ch, font)));
      const room = el.width + 1e-9 - afmMeasurer.width(literal, font);
      maxLength = Math.floor(room / (widest * fields.length));
      if (font.sizeMm > el.height + 1e-9 || maxLength < 1) {
        problem(o, 'TEXT_FIT');
        continue;
      }
      marked = { ...el, text: withMarks, lines: [withMarks], overflow: false };
    } else if (el.kind === 'barcode1d') {
      if (plan.kind === 'CODE128') {
        const widthDots = mmToDots(el.width, dpi);
        const sample = row[fields[0]!]!;
        const m = moduleDots(widthDots, code128WorstModules(Math.max(sample.length, 1)));
        if (m < 1) {
          problem(o, 'BARCODE_SIZE');
          continue;
        }
        freezes.set(o.id, { moduleDots: m });
        maxLength = Math.floor((Math.floor(widthDots / m) - 35) / 11);
      } else {
        maxLength = plan.kind === 'EAN13' ? 12 : plan.kind === 'UPCA' ? 11 : 7;
      }
      marked = applyFreeze({ ...el, printerData: withMarks, humanText: withMarks }, freezes.get(o.id), dpi);
    } else if (el.kind === 'barcode2d') {
      const boxDots = mmToDots(Math.min(el.width, el.height), dpi);
      const m = el.moduleDots;
      const maxModules = Math.floor(boxDots / m);
      const modulesFor = (bytes: number) => {
        try {
          return symbolSize2D('QR', 'a'.repeat(bytes), el.ecLevel).rows;
        } catch {
          return Infinity; // passou da capacidade do QR
        }
      };
      let lo = 0;
      let hi = 3000;
      while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2);
        if (modulesFor(mid) <= maxModules) lo = mid;
        else hi = mid - 1;
      }
      maxLength = Math.floor((lo - utf8Length(literal)) / fields.length);
      if (maxLength < 1) {
        problem(o, 'BARCODE_SIZE');
        continue;
      }
      freezes.set(o.id, { moduleDots: m, symbolModules: modulesFor(lo) });
      marked = applyFreeze({ ...el, data: withMarks }, freezes.get(o.id), dpi);
    } else {
      problem(o, 'DYNAMIC_IMAGE');
      continue;
    }

    const cmd = gen.element(marked, ctx);
    if (lostCharset(ctx)) {
      problem(o, 'CHARSET');
      continue;
    }
    const cut = cmd.split(MARK);
    const ids = fields.map((field) => {
      const id = `s${slots.length + 1}`;
      slots.push({ id, field, kind: plan.kind, transform: plan.kind === 'TEXT' || plan.kind === 'QR' || plan.kind === 'CODE128' ? 'NONE' : 'GS1_DATA', maxLength });
      return id;
    });
    cut.forEach((piece, i) => (i % 2 === 0 ? lit(piece) : segments.push({ slot: ids[Number(piece)]! })));
  }
  const [before, after] = packageFooter(language, '\u0003').split('\u0003');
  lit(before!);
  segments.push({ copies: true });
  lit(after!);
  return { segments, slots, freezes };
}

/** Bytes pelo caminho do PC (resolver + gerador), com o módulo congelado do pacote. */
function referenceBytes(
  template: LabelTemplate,
  visible: LabelObject[],
  target: TargetId,
  freezes: Map<string, Freeze>,
  values: Readonly<Record<string, string>>,
  copies: number,
): Uint8Array {
  const gen = GENERATORS[target.language];
  const resolved = resolveLabel({ ...template, objects: visible }, values, { dpi: target.dpi, checkGeometry: false, barcodeDetail: 'size' });
  const ctx: ElementContext = { dpi: target.dpi, issues: [] };
  let commands = gen.header(template.widthMm, template.heightMm, target.dpi);
  for (const el of resolved.elements) commands += gen.element(applyFreeze(el, freezes.get(el.id), target.dpi), ctx);
  commands += packageFooter(target.language, String(copies));
  return gen.encode(commands);
}

/** Corta por pontos de código (não quebra acento nem par substituto). */
const cutTo = (s: string, n: number) => [...s].slice(0, n).join('');

function vectorValues(target: CompiledTarget, language: ColetorLanguage, which: 0 | 1, samples: Readonly<Record<string, string>>): Record<string, string> {
  const byField = new Map<string, PackageSlot[]>();
  for (const s of target.slots) byField.set(s.field, [...(byField.get(s.field) ?? []), s]);
  const out: Record<string, string> = {};
  for (const [field, slots] of byField) {
    const accepts = (v: string) =>
      slots.every((s) => {
        try {
          fillSlot(s, v, language);
          return true;
        } catch {
          return false;
        }
      });
    const sample = samples[field];
    if (which === 0 && sample !== undefined && accepts(sample)) {
      out[field] = sample;
      continue;
    }
    const kind = KIND_PRIORITY.find((k) => slots.some((s) => s.kind === k))!;
    const limit = Math.min(...slots.map((s) => s.maxLength));
    const base = DEFAULTS[kind][which];
    out[field] = kind === 'TEXT' || kind === 'QR' || kind === 'CODE128' ? cutTo(base, limit) : base;
  }
  return out;
}

function checkInput(input: ColetorCompileInput): void {
  if (!Number.isInteger(input.templateVersion) || input.templateVersion < 1) throw new PackageInputError('Grave uma versão do template antes de publicar');
  if (input.targets.length === 0) throw new PackageInputError('Escolha pelo menos um alvo (linguagem + DPI)');
  const seen = new Set<string>();
  for (const t of input.targets) {
    if (t.language !== 'ZPL' && t.language !== 'TSPL') throw new PackageInputError(`Linguagem ${String(t.language)} não vai ao coletor (só ZPL e TSPL)`);
    if (!COLETOR_DPIS.includes(t.dpi)) throw new PackageInputError(`DPI ${t.dpi} fora de ${COLETOR_DPIS.join('/')}`);
    const key = `${t.language}/${t.dpi}`;
    if (seen.has(key)) throw new PackageInputError(`Alvo ${key} repetido`);
    seen.add(key);
  }
}

export async function compileColetorPackage(input: ColetorCompileInput): Promise<LabelPackage> {
  checkInput(input);
  const { template } = input;
  const hash = await sha256Hex(canonicalJson(template));
  if (hash !== input.templateHash) throw new PackageInputError('templateHash não confere com o template enviado');

  const samples = input.samples ?? {};
  const visible = sortedByZ(template.objects.filter((o) => o.visible));
  const problems: PortabilityProblem[] = [];
  const plans = new Map<string, DynamicPlan>();
  for (const o of visible) {
    const p = planObject(o);
    if (typeof p === 'string') problems.push({ objectId: o.id, objectName: o.name, reason: p });
    else if (p) plans.set(o.id, p);
  }

  const sampleFor = (field: string, kind: SlotKind) => {
    const s = samples[field];
    if (s !== undefined && s !== '' && !CONTROL.test(s)) {
      if (kind === 'TEXT' || kind === 'QR') return s;
      try {
        normalize1D(kind, s);
        return s;
      } catch {
        // exemplo inválido para o código: usa o padrão
      }
    }
    return DEFAULTS[kind][0];
  };

  const compiled = input.targets.map((t) => compileTarget(template, visible, plans, t, sampleFor, problems));
  if (problems.length > 0) {
    const seen = new Set<string>();
    throw new NotPortableError(problems.filter((p) => !seen.has(`${p.objectId}|${p.reason}`) && seen.add(`${p.objectId}|${p.reason}`)));
  }

  const targets: PackageTarget[] = [];
  for (const [i, t] of input.targets.entries()) {
    const c = compiled[i]!;
    const conformance: ConformanceVector[] = [];
    for (const [which, copies] of [[0, 1], [1, 3]] as const) {
      const values = vectorValues(c, t.language, which, samples);
      const pkgTarget: PackageTarget = { ...t, segments: c.segments, slots: c.slots, conformance: [] };
      const assembled = assembleLabel(pkgTarget, values, copies);
      const reference = referenceBytes(template, visible, t, c.freezes, values, copies);
      if (!sameBytes(assembled, reference)) {
        throw new Error(`Compilador inconsistente no alvo ${t.language}/${t.dpi}: pacote e gerador divergem (nada foi publicado)`);
      }
      conformance.push({ values, copies, bytesSha256: await sha256Hex(assembled) });
    }
    targets.push({ language: t.language, dpi: t.dpi, segments: c.segments, slots: c.slots, conformance });
  }

  return {
    format: COLETOR_FORMAT,
    formatVersion: COLETOR_FORMAT_VERSION,
    templateId: template.id,
    name: template.name,
    templateVersion: input.templateVersion,
    templateHash: input.templateHash,
    widthMm: template.widthMm,
    heightMm: template.heightMm,
    compiler: COLETOR_COMPILER,
    compiledAt: (input.now ?? new Date()).toISOString(),
    fields: packageFields(plans, targets, samples),
    targets,
  };
}

const INPUT_LENGTH: Partial<Record<SlotKind, number>> = { EAN13: 13, EAN8: 8, UPCA: 12 };

function packageFields(plans: Map<string, DynamicPlan>, targets: PackageTarget[], samples: Readonly<Record<string, string>>): PackageField[] {
  // Ordem na tela = ordem em que o campo aparece no template (camadas de baixo para cima).
  const order: string[] = [];
  for (const p of plans.values()) for (const piece of p.pieces) if ('field' in piece && !order.includes(piece.field)) order.push(piece.field);
  return order.map((name) => {
    const slots = targets.flatMap((t) => t.slots.filter((s) => s.field === name));
    const field: PackageField = {
      name,
      label: name,
      required: true,
      // EAN/UPC: o operador pode digitar com o verificador.
      maxLength: Math.min(...slots.map((s) => INPUT_LENGTH[s.kind] ?? s.maxLength)),
      input: slots.some((s) => s.kind !== 'TEXT' && s.kind !== 'QR') ? 'SCAN' : 'ANY',
    };
    if (samples[name] !== undefined) field.sample = samples[name];
    return field;
  });
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
