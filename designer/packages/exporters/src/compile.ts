import {
  compileString,
  contentOf,
  resolveLabel,
  sortedByZ,
  type BrandTheme,
  type Issue,
  type LabelTemplate,
  type PrinterLanguage,
  type TextMeasurer,
} from '@athos-label/engine';
import { epl } from './epl';
import type { CommandGenerator, ElementContext } from './printer';
import { tspl } from './tspl';
import { zpl } from './zpl';

export const GENERATORS: Record<PrinterLanguage, CommandGenerator> = { ZPL: zpl, TSPL: tspl, EPL: epl };

export interface CompileOptions {
  language: PrinterLanguage;
  dpi: number;
  measurer?: TextMeasurer;
  brand?: BrandTheme;
  rasterizeText?: ElementContext['rasterizeText'];
  /** Relógio para TODAY()/NOW(); padrão = hora de cada render. */
  now?: () => Date;
}

export interface CompiledLabel {
  language: PrinterLanguage;
  dpi: number;
  /** Comandos de uma etiqueta completa (cabeçalho + objetos + rodapé com cópias). */
  /** `seqOffset`: início da faixa de numeração do job (só o SEQ soma). */
  render(row: Readonly<Record<string, unknown>>, index: number, copies?: number, seqOffset?: number): { commands: string; issues: Issue[] };
  encode(commands: string): Uint8Array;
  stats: { staticObjects: number; dynamicObjects: number };
}

/**
 * Cache de template (o "segredo" do Bartender para 1.000 etiquetas em segundos): o template é
 * compilado UMA vez. Objetos estáticos (texto fixo, caixas, logo) viram comando uma única vez;
 * por etiqueta, só os objetos que dependem de dados são resolvidos e gerados.
 */
export function compileLabel(template: LabelTemplate, opts: CompileOptions): CompiledLabel {
  const gen = GENERATORS[opts.language];
  const visible = sortedByZ(template.objects.filter((o) => o.visible));
  const isDynamic = (content: string | null) => {
    if (content === null) return false;
    try {
      return !compileString(content).isStatic;
    } catch {
      return true; // erro de template aparece por linha, como em qualquer objeto dinâmico
    }
  };
  const dynamicIds = new Set(visible.filter((o) => isDynamic(contentOf(o))).map((o) => o.id));
  // ZPL/TSPL mandam o dado e a impressora gera o símbolo: basta medir (10x mais rápido com QR).
  // EPL desenha 2D como gráfico, então precisa da matriz.
  const barcodeDetail = opts.language === 'EPL' ? ('full' as const) : ('size' as const);
  const resolveOpts = { dpi: opts.dpi, measurer: opts.measurer, brand: opts.brand, checkGeometry: false, barcodeDetail };

  const staticCtx: ElementContext = { dpi: opts.dpi, issues: [], rasterizeText: opts.rasterizeText };
  const staticResolved = resolveLabel({ ...template, objects: visible.filter((o) => !dynamicIds.has(o.id)) }, {}, resolveOpts);
  const staticCommands = new Map(staticResolved.elements.map((el) => [el.id, gen.element(el, staticCtx)]));
  const staticIssues = [...staticResolved.issues, ...staticCtx.issues];

  const header = gen.header(template.widthMm, template.heightMm, opts.dpi);
  const dynTemplate = { ...template, objects: visible.filter((o) => dynamicIds.has(o.id)) };

  return {
    language: opts.language,
    dpi: opts.dpi,
    stats: { staticObjects: visible.length - dynamicIds.size, dynamicObjects: dynamicIds.size },
    encode: (c) => gen.encode(c),
    render(row, index, copies = 1, seqOffset = 0) {
      const ctx: ElementContext = { dpi: opts.dpi, issues: [], rasterizeText: opts.rasterizeText };
      const resolved = dynamicIds.size
        ? resolveLabel(dynTemplate, row, { ...resolveOpts, index, seqOffset, now: opts.now?.() })
        : { elements: [], issues: [] };
      const dyn = new Map(resolved.elements.map((el) => [el.id, el]));
      let commands = header;
      for (const o of visible) {
        const el = dyn.get(o.id);
        commands += el ? gen.element(el, ctx) : staticCommands.get(o.id) ?? '';
      }
      commands += gen.footer(copies);
      return { commands, issues: [...staticIssues, ...resolved.issues, ...ctx.issues] };
    },
  };
}
