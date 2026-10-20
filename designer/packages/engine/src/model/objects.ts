/**
 * Modelo da etiqueta. Toda geometria em mm; a conversão para dots acontece só na exportação,
 * com o DPI do perfil da impressora de destino.
 *
 * Todo conteúdo que pode variar por linha de dados é uma *string de template*:
 *   "Lote {{LOTE}}", "{{PRECO * QTD | currency}}", "{{SEQ(1, 1, 6)}}".
 * Um único mecanismo (data/template-string.ts) cobre campo dinâmico, fórmula, transformação
 * e auto-incremento; texto sem `{{` é estático.
 */

export type ObjectType = 'TEXT' | 'BARCODE_1D' | 'BARCODE_2D' | 'IMAGE' | 'BOX';

export interface BaseLabelObject {
  id: string;
  type: ObjectType;
  /** Nome amigável na lista de camadas. */
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
  /** Graus, sentido horário, em torno do canto superior esquerdo (mesma convenção do Konva). */
  rotation: number;
  zIndex: number;
  visible: boolean;
  /** Trava edição no designer. NÃO afeta impressão. */
  locked: boolean;
}

export type TextAlignment = 'LEFT' | 'CENTER' | 'RIGHT';
export type VerticalAlignment = 'TOP' | 'MIDDLE' | 'BOTTOM';

/**
 * NONE: uma linha, corta o que sobrar. WRAP: quebra linhas no tamanho fixo.
 * SHRINK: uma linha, reduz a fonte até caber. WRAP_SHRINK: quebra e reduz (o "auto-fit").
 */
export type TextFit = 'NONE' | 'WRAP' | 'SHRINK' | 'WRAP_SHRINK';

export type BrandFontRole = 'heading' | 'body';

export interface FontConfig {
  family: string;
  /** Tamanho da fonte (em) em mm. Unidade física, independente de DPI. */
  sizeMm: number;
  bold: boolean;
  italic: boolean;
}

export interface TextObject extends BaseLabelObject {
  type: 'TEXT';
  content: string;
  font: FontConfig;
  alignment: TextAlignment;
  verticalAlignment: VerticalAlignment;
  fit: TextFit;
  /** Piso do auto-fit. */
  minSizeMm: number;
  /**
   * Hifenização em português nas quebras de linha. Opcional e desligada por padrão: templates
   * salvos antes de 2026-10-19 não têm o campo e quebram exatamente como antes.
   */
  hyphenate?: boolean;
  /** Fonte vem do kit de marca (null = usa `font.family` do próprio objeto). */
  brandFont: BrandFontRole | null;
  color: string;
  useBrandColor: boolean;
}

export type Symbology1D = 'CODE128' | 'EAN13' | 'UPCA' | 'CODE39' | 'EAN8' | 'ITF14' | 'GS1_128';

export interface Barcode1DObject extends BaseLabelObject {
  type: 'BARCODE_1D';
  symbology: Symbology1D;
  content: string;
  /** Mostra os dígitos abaixo das barras. */
  humanReadable: boolean;
  textSizeMm: number;
  /** Zona de silêncio exigida de cada lado, fora da caixa do objeto (validação). */
  quietZoneMm: number;
}

export type Symbology2D = 'QR' | 'DATAMATRIX' | 'PDF417';
export type QrErrorLevel = 'L' | 'M' | 'Q' | 'H';

export interface Barcode2DObject extends BaseLabelObject {
  type: 'BARCODE_2D';
  symbology: Symbology2D;
  content: string;
  ecLevel: QrErrorLevel;
}

/** Bitmap 1 bit por pixel, linhas alinhadas a byte, bit 1 = preto (convenção do ZPL). */
export interface MonoBitmapData {
  width: number;
  height: number;
  /** Base64 dos bytes. */
  data: string;
}

export interface ImageObject extends BaseLabelObject {
  type: 'IMAGE';
  /** Data URL original (PNG/JPEG), usada em SVG/PDF. */
  src: string;
  /** Versão monocromática para impressora térmica, gerada na importação. */
  mono: MonoBitmapData | null;
  threshold: number;
}

export interface BoxObject extends BaseLabelObject {
  type: 'BOX';
  borderMm: number;
  filled: boolean;
  color: string;
  useBrandColor: boolean;
}

export type LabelObject = TextObject | Barcode1DObject | Barcode2DObject | ImageObject | BoxObject;

export type PrinterLanguage = 'ZPL' | 'TSPL' | 'EPL';

export interface LabelTemplate {
  id: string;
  name: string;
  widthMm: number;
  heightMm: number;
  /** DPI da impressora de destino usado na prévia (módulo de código de barras em dots inteiros). */
  dpi: number;
  objects: LabelObject[];
  /** Template base de onde este herda (ver templates/inheritance.ts). */
  baseTemplateId: string | null;
}

export interface PrinterProfile {
  id: string;
  name: string;
  language: PrinterLanguage;
  dpi: number;
  host: string;
  port: number;
}

// O engine não depende de lib DOM; randomUUID existe em browser, Web Worker e Node >= 19.
declare const crypto: { randomUUID(): string };

export function createId(): string {
  return crypto.randomUUID();
}

export function createTemplate(widthMm = 50, heightMm = 30, name = 'Nova etiqueta'): LabelTemplate {
  return { id: createId(), name, widthMm, heightMm, dpi: 203, objects: [], baseTemplateId: null };
}

type Partial2<T> = Partial<Omit<T, 'type'>>;

const base = (name: string, w: number, h: number): Omit<BaseLabelObject, 'type'> => ({
  id: createId(),
  name,
  x: 2,
  y: 2,
  width: w,
  height: h,
  rotation: 0,
  zIndex: 0,
  visible: true,
  locked: false,
});

export function createTextObject(p: Partial2<TextObject> = {}): TextObject {
  return {
    ...base('Texto', 30, 5),
    type: 'TEXT',
    content: 'Texto',
    font: { family: 'Helvetica', sizeMm: 3, bold: false, italic: false },
    alignment: 'LEFT',
    verticalAlignment: 'MIDDLE',
    fit: 'WRAP_SHRINK',
    minSizeMm: 1.5,
    brandFont: null,
    color: '#000000',
    useBrandColor: false,
    ...p,
  };
}

export function createBarcode1D(p: Partial2<Barcode1DObject> = {}): Barcode1DObject {
  return {
    ...base('Código de barras', 40, 15),
    type: 'BARCODE_1D',
    symbology: 'CODE128',
    content: '{{SKU}}',
    humanReadable: true,
    textSizeMm: 2.5,
    quietZoneMm: 2,
    ...p,
  };
}

export function createBarcode2D(p: Partial2<Barcode2DObject> = {}): Barcode2DObject {
  return {
    ...base('QR Code', 15, 15),
    type: 'BARCODE_2D',
    symbology: 'QR',
    content: '{{SKU}}',
    ecLevel: 'M',
    ...p,
  };
}

export function createImageObject(p: Partial2<ImageObject> & Pick<ImageObject, 'src'>): ImageObject {
  return { ...base('Imagem', 15, 15), type: 'IMAGE', mono: null, threshold: 128, ...p };
}

export function createBox(p: Partial2<BoxObject> = {}): BoxObject {
  return {
    ...base('Caixa', 20, 10),
    type: 'BOX',
    borderMm: 0.3,
    filled: false,
    color: '#000000',
    useBrandColor: false,
    ...p,
  };
}

/** Objetos com string de template (os que podem depender de dados). */
export function contentOf(obj: LabelObject): string | null {
  return obj.type === 'TEXT' || obj.type === 'BARCODE_1D' || obj.type === 'BARCODE_2D' ? obj.content : null;
}
