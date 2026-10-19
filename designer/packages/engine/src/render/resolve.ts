import { BarcodeError, encode1D, encode2D, moduleCount1D, moduleDots, symbolSize2D } from '../barcode/encode';
import { compileString, renderString, TemplateError } from '../data/template-string';
import { boundsOf, containsBox } from '../geometry/bbox';
import { dotsToMm, mmToDots } from '../geometry/units';
import { DEFAULT_BRAND, resolveStyle, type BrandTheme } from '../model/brand';
import type {
  Barcode1DObject,
  Barcode2DObject,
  LabelObject,
  LabelTemplate,
  MonoBitmapData,
  QrErrorLevel,
  Symbology1D,
  Symbology2D,
  TextAlignment,
  VerticalAlignment,
} from '../model/objects';
import { sortedByZ } from '../model/template-ops';
import { afmMeasurer, type TextMeasurer } from '../layout/metrics';
import { fitText } from '../layout/text-layout';

/**
 * Layout Resolver: template + linha de dados -> elementos prontos para desenhar/exportar.
 * É o único lugar que avalia templates, mede texto e escolhe módulo de código de barras; prévia,
 * SVG, PDF, ZPL, TSPL e EPL consomem o resultado, então todos concordam entre si.
 */

export type IssueSeverity = 'error' | 'warning';

export type IssueCode =
  | 'TEMPLATE_ERROR'
  | 'MISSING_FIELD'
  | 'EMPTY_VALUE'
  | 'TEXT_OVERFLOW'
  | 'BARCODE_INVALID'
  | 'BARCODE_TOO_SMALL'
  | 'QUIET_ZONE'
  | 'OUT_OF_BOUNDS'
  | 'SAFETY_MARGIN'
  | 'UNSUPPORTED';

export interface Issue {
  severity: IssueSeverity;
  code: IssueCode;
  message: string;
  objectId?: string;
  field?: string;
}

interface Frame {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  rotation: number;
  /** O conteúdo depende da linha de dados (não pode ser cacheado entre etiquetas). */
  dynamic: boolean;
}

export interface ResolvedText extends Frame {
  kind: 'text';
  text: string;
  lines: string[];
  fontSizeMm: number;
  lineHeightMm: number;
  family: string;
  bold: boolean;
  italic: boolean;
  align: TextAlignment;
  valign: VerticalAlignment;
  color: string;
  overflow: boolean;
}

export interface ResolvedBarcode1D extends Frame {
  kind: 'barcode1d';
  symbology: Symbology1D;
  printerData: string;
  humanText: string;
  /** Um item por módulo (1 = barra). Vazio no modo 'size' (a impressora gera as barras). */
  modules: Uint8Array;
  moduleCount: number;
  moduleDots: number;
  moduleMm: number;
  /** Largura real ocupada pelas barras (módulo inteiro em dots). */
  barsWidthMm: number;
  barHeightMm: number;
  humanReadable: boolean;
  textSizeMm: number;
}

export interface ResolvedBarcode2D extends Frame {
  kind: 'barcode2d';
  symbology: Symbology2D;
  data: string;
  ecLevel: QrErrorLevel;
  /** Matriz desenhada (vazia no modo 'size'). */
  rows: number;
  cols: number;
  matrix: Uint8Array;
  /** Lado do símbolo no pior caso: define o módulo e a caixa ocupada (igual em prévia e impressão). */
  symbolModules: number;
  moduleDots: number;
  moduleMm: number;
}

export interface ResolvedImage extends Frame {
  kind: 'image';
  src: string;
  mono: MonoBitmapData | null;
}

export interface ResolvedBox extends Frame {
  kind: 'box';
  borderMm: number;
  filled: boolean;
  color: string;
}

/** Objeto que não pôde ser resolvido (ex.: EAN inválido). A prévia mostra; a impressão pula. */
export interface ResolvedError extends Frame {
  kind: 'error';
  message: string;
}

export type ResolvedElement =
  | ResolvedText
  | ResolvedBarcode1D
  | ResolvedBarcode2D
  | ResolvedImage
  | ResolvedBox
  | ResolvedError;

export interface ResolvedLabel {
  widthMm: number;
  heightMm: number;
  dpi: number;
  elements: ResolvedElement[];
  issues: Issue[];
}

export interface ResolveOptions {
  dpi?: number;
  measurer?: TextMeasurer;
  brand?: BrandTheme;
  /** Posição da etiqueta no lote (SEQ, $index). */
  index?: number;
  now?: Date;
  /** Checagens de geometria do template (uma vez por lote basta). */
  checkGeometry?: boolean;
  /**
   * 'full' (padrão): codifica barras/matriz para desenhar (prévia, SVG, PDF, EPL).
   * 'size': só valida e mede; ZPL/TSPL mandam o dado e a impressora gera o símbolo.
   */
  barcodeDetail?: 'full' | 'size';
}

const frameOf = (o: LabelObject, dynamic: boolean): Frame => ({
  id: o.id,
  x: o.x,
  y: o.y,
  width: o.width,
  height: o.height,
  rotation: o.rotation,
  dynamic,
});

export function resolveLabel(
  template: LabelTemplate,
  row: Readonly<Record<string, unknown>>,
  opts: ResolveOptions = {},
): ResolvedLabel {
  const dpi = opts.dpi ?? template.dpi;
  const measurer = opts.measurer ?? afmMeasurer;
  const brand = opts.brand ?? DEFAULT_BRAND;
  const missing = new Set<string>();
  const ctx = { row, index: opts.index ?? 0, now: opts.now ?? new Date(), missing };
  const issues: Issue[] = [];
  const elements: ResolvedElement[] = [];

  for (const obj of sortedByZ(template.objects)) {
    if (!obj.visible) continue;
    try {
      elements.push(resolveObject(obj));
    } catch (e) {
      const isData = e instanceof TemplateError || e instanceof BarcodeError;
      if (!isData) throw e;
      issues.push({
        severity: 'error',
        code: e instanceof BarcodeError ? 'BARCODE_INVALID' : 'TEMPLATE_ERROR',
        message: `${obj.name}: ${e.message}`,
        objectId: obj.id,
      });
      elements.push({ ...frameOf(obj, true), kind: 'error', message: e.message });
    }
  }

  for (const field of missing) {
    issues.push({ severity: 'error', code: 'MISSING_FIELD', message: `Campo {{${field}}} não existe nos dados`, field });
  }
  if (opts.checkGeometry !== false) issues.push(...checkGeometry(template, brand));
  return { widthMm: template.widthMm, heightMm: template.heightMm, dpi, elements, issues };

  function render(obj: LabelObject & { content: string }): { text: string; dynamic: boolean } {
    const compiled = compileString(obj.content);
    const text = renderString(compiled, ctx);
    if (!compiled.isStatic && text.trim() === '') {
      issues.push({ severity: 'warning', code: 'EMPTY_VALUE', message: `${obj.name}: valor vazio`, objectId: obj.id });
    }
    return { text, dynamic: !compiled.isStatic };
  }

  function resolveObject(obj: LabelObject): ResolvedElement {
    switch (obj.type) {
      case 'TEXT': {
        const { text, dynamic } = render(obj);
        const style = resolveStyle(obj, brand);
        const font = { family: style.family!, sizeMm: obj.font.sizeMm, bold: style.bold!, italic: obj.font.italic };
        const fit = fitText(
          { text, widthMm: obj.width, heightMm: obj.height, font, fit: obj.fit, minSizeMm: obj.minSizeMm },
          measurer,
        );
        if (fit.overflow) {
          issues.push({
            severity: 'warning',
            code: 'TEXT_OVERFLOW',
            message: `${obj.name}: texto não cabe nem em ${obj.minSizeMm} mm e foi cortado`,
            objectId: obj.id,
          });
        }
        return {
          ...frameOf(obj, dynamic),
          kind: 'text',
          text,
          lines: fit.lines,
          fontSizeMm: fit.sizeMm,
          lineHeightMm: fit.lineHeightMm,
          family: font.family,
          bold: font.bold,
          italic: obj.font.italic,
          align: obj.alignment,
          valign: obj.verticalAlignment,
          color: style.color,
          overflow: fit.overflow,
        };
      }
      case 'BARCODE_1D':
        return resolve1D(obj, render(obj));
      case 'BARCODE_2D':
        return resolve2D(obj, render(obj));
      case 'IMAGE':
        return { ...frameOf(obj, false), kind: 'image', src: obj.src, mono: obj.mono };
      case 'BOX': {
        const style = resolveStyle(obj, brand);
        return { ...frameOf(obj, false), kind: 'box', borderMm: obj.borderMm, filled: obj.filled, color: style.color };
      }
    }
  }

  function resolve1D(obj: Barcode1DObject, r: { text: string; dynamic: boolean }): ResolvedBarcode1D {
    const enc = opts.barcodeDetail === 'size' ? null : encode1D(obj.symbology, r.text);
    const meta = enc
      ? { printerData: enc.printerData, humanText: enc.humanText, count: enc.modules.length }
      : moduleCount1D(obj.symbology, r.text);
    let m = moduleDots(mmToDots(obj.width, dpi), meta.count);
    if (m === 0) {
      issues.push({
        severity: 'error',
        code: 'BARCODE_TOO_SMALL',
        message: `${obj.name}: ${meta.count} módulos não cabem em ${obj.width} mm a ${dpi} dpi (precisa de ${dotsToMm(meta.count, dpi).toFixed(1)} mm)`,
        objectId: obj.id,
      });
      m = 1;
    }
    const barsWidthMm = dotsToMm(m * meta.count, dpi);
    const qz = obj.quietZoneMm;
    if (obj.rotation % 360 === 0 && (obj.x - qz < 0 || obj.x + barsWidthMm + qz > template.widthMm)) {
      issues.push({
        severity: 'warning',
        code: 'QUIET_ZONE',
        message: `${obj.name}: falta zona de silêncio de ${qz} mm (o leitor pode falhar)`,
        objectId: obj.id,
      });
    }
    const textSpace = obj.humanReadable ? obj.textSizeMm * 1.2 : 0;
    return {
      ...frameOf(obj, r.dynamic),
      kind: 'barcode1d',
      symbology: obj.symbology,
      printerData: meta.printerData,
      humanText: meta.humanText,
      modules: enc ? enc.modules : new Uint8Array(0),
      moduleCount: meta.count,
      moduleDots: m,
      moduleMm: dotsToMm(m, dpi),
      barsWidthMm,
      barHeightMm: Math.max(1, obj.height - textSpace),
      humanReadable: obj.humanReadable,
      textSizeMm: obj.textSizeMm,
    };
  }

  function resolve2D(obj: Barcode2DObject, r: { text: string; dynamic: boolean }): ResolvedBarcode2D {
    const size = symbolSize2D(obj.symbology, r.text, obj.ecLevel);
    const symbolModules = Math.max(size.rows, size.cols);
    const enc = opts.barcodeDetail === 'size' ? null : encode2D(obj.symbology, r.text, obj.ecLevel);
    let m = moduleDots(mmToDots(Math.min(obj.width, obj.height), dpi), symbolModules, 10);
    if (m === 0) {
      issues.push({
        severity: 'error',
        code: 'BARCODE_TOO_SMALL',
        message: `${obj.name}: matriz ${size.cols}x${size.rows} não cabe em ${Math.min(obj.width, obj.height)} mm`,
        objectId: obj.id,
      });
      m = 1;
    }
    return {
      ...frameOf(obj, r.dynamic),
      kind: 'barcode2d',
      symbology: obj.symbology,
      data: r.text,
      ecLevel: obj.ecLevel,
      rows: enc ? enc.rows : size.rows,
      cols: enc ? enc.cols : size.cols,
      matrix: enc ? enc.matrix : new Uint8Array(0),
      symbolModules,
      moduleDots: m,
      moduleMm: dotsToMm(m, dpi),
    };
  }
}

/** Objetos fora da etiqueta (erro) ou dentro da margem de segurança do kit de marca (alerta). */
export function checkGeometry(template: LabelTemplate, brand: BrandTheme = DEFAULT_BRAND): Issue[] {
  const label = { x: 0, y: 0, width: template.widthMm, height: template.heightMm };
  const m = brand.safetyMarginMm;
  const safe = { x: m, y: m, width: template.widthMm - 2 * m, height: template.heightMm - 2 * m };
  const out: Issue[] = [];
  for (const o of template.objects) {
    if (!o.visible) continue;
    const box = boundsOf(o);
    if (!containsBox(label, box)) {
      out.push({ severity: 'error', code: 'OUT_OF_BOUNDS', message: `${o.name}: sai da etiqueta`, objectId: o.id });
    } else if (m > 0 && !containsBox(safe, box)) {
      out.push({ severity: 'warning', code: 'SAFETY_MARGIN', message: `${o.name}: dentro da margem de segurança de ${m} mm`, objectId: o.id });
    }
  }
  return out;
}

/** Todos os campos de dados que o template usa (para mapeamento de colunas). */
export function templateFields(template: LabelTemplate): string[] {
  const out = new Set<string>();
  for (const o of template.objects) {
    if (o.type !== 'TEXT' && o.type !== 'BARCODE_1D' && o.type !== 'BARCODE_2D') continue;
    try {
      compileString(o.content).fields.forEach((f) => out.add(f));
    } catch {
      // Template inválido aparece como TEMPLATE_ERROR na resolução; aqui só listamos campos.
    }
  }
  return [...out].sort();
}
