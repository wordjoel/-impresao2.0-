import type { Issue, MonoBitmap, PrinterLanguage, ResolvedElement, ResolvedLabel, ResolvedText } from '@athos-label/engine';

export interface ElementContext {
  dpi: number;
  issues: Issue[];
  /** Texto como imagem: pixel-exato com a prévia (o app fornece, via canvas). */
  rasterizeText?: (el: ResolvedText, dpi: number) => MonoBitmap | null;
}

/** Um gerador de comandos nativos. Cabeçalho, elemento e rodapé separados = cache por objeto. */
export interface CommandGenerator {
  language: PrinterLanguage;
  header(widthMm: number, heightMm: number, dpi: number): string;
  element(el: ResolvedElement, ctx: ElementContext): string;
  footer(copies: number): string;
  /** Bytes para o socket (ZPL: UTF-8 com ^CI28; TSPL/EPL: 1 byte por caractere, Windows-1252). */
  encode(commands: string): Uint8Array;
}

export interface PrinterExportOptions {
  dpi?: number;
  copies?: number;
  rasterizeText?: ElementContext['rasterizeText'];
}

export interface PrinterOutput {
  commands: string;
  issues: Issue[];
}

export function labelToCommands(gen: CommandGenerator, label: ResolvedLabel, opts: PrinterExportOptions = {}): PrinterOutput {
  const dpi = opts.dpi ?? label.dpi;
  const ctx: ElementContext = { dpi, issues: [], rasterizeText: opts.rasterizeText };
  let out = gen.header(label.widthMm, label.heightMm, dpi);
  for (const el of label.elements) out += gen.element(el, ctx);
  out += gen.footer(opts.copies ?? 1);
  return { commands: out, issues: ctx.issues };
}
