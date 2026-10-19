import { afmMeasurer, MonoBitmap, mmToDots, type MeasureFont, type ResolvedText, type TextMeasurer } from '@athos-label/engine';
import { layoutLines } from '@athos-label/exporters';

/**
 * Medição no app: Helvetica/Arial usam a tabela AFM do engine (métrica idêntica, determinística e
 * igual ao PDF); qualquer outra fonte (ex.: a do kit de marca) é medida no canvas, com a fonte
 * real instalada. Funciona na thread principal e no Worker (OffscreenCanvas).
 */
const AFM_FAMILIES = new Set(['helvetica', 'arial', 'sans-serif']);

type Ctx2D = OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D;

let ctx: Ctx2D | null = null;
function context(): Ctx2D | null {
  if (ctx) return ctx;
  if (typeof OffscreenCanvas !== 'undefined') ctx = new OffscreenCanvas(1, 1).getContext('2d');
  else if (typeof document !== 'undefined') ctx = document.createElement('canvas').getContext('2d');
  return ctx;
}

export function cssFont(f: { family: string; bold: boolean; italic?: boolean }, sizePx: number): string {
  return `${f.italic ? 'italic ' : ''}${f.bold ? 'bold ' : ''}${sizePx}px "${f.family}", Helvetica, Arial, sans-serif`;
}

export const appMeasurer: TextMeasurer = {
  width(text: string, font: MeasureFont) {
    const c = AFM_FAMILIES.has(font.family.toLowerCase()) ? null : context();
    if (!c) return afmMeasurer.width(text, font);
    c.font = cssFont(font, 100);
    return (c.measureText(text).width / 100) * font.sizeMm;
  },
};

/**
 * "Texto como imagem": desenha o texto do objeto no DPI da impressora e binariza. O que sai na
 * etiqueta fica idêntico à prévia, com qualquer fonte, ao custo de payload maior.
 */
export function rasterizeText(el: ResolvedText, dpi: number): MonoBitmap | null {
  const w = Math.max(1, mmToDots(el.width, dpi));
  const h = Math.max(1, mmToDots(el.height, dpi));
  const canvas = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(w, h) : null;
  const c = canvas?.getContext('2d');
  if (!canvas || !c) return null;
  const k = dpi / 25.4;
  c.fillStyle = '#fff';
  c.fillRect(0, 0, w, h);
  c.fillStyle = '#000';
  c.font = cssFont(el, el.fontSizeMm * k);
  for (const l of layoutLines(el)) {
    c.textAlign = l.anchor === 'start' ? 'left' : l.anchor === 'middle' ? 'center' : 'right';
    c.fillText(l.text, l.anchorX * k, l.baseline * k);
  }
  return MonoBitmap.fromRgba(c.getImageData(0, 0, w, h).data, w, h, 140);
}
