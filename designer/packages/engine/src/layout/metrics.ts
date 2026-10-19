/**
 * Medição de texto. Interface plugável:
 *  - `afmMeasurer` (padrão): larguras AFM da Helvetica/Helvetica-Bold (métrica idêntica à Arial).
 *    Pura e determinística: roda em Web Worker, Node e testes, sem DOM.
 *  - no navegador, o app injeta um medidor de canvas (fonte real instalada).
 */

export interface MeasureFont {
  family: string;
  sizeMm: number;
  bold: boolean;
  italic?: boolean;
}

export interface TextMeasurer {
  /** Largura do texto em mm. */
  width(text: string, font: MeasureFont): number;
}

// Larguras em 1/1000 em para os caracteres 32..126 (AFM padrão Adobe).
const HELVETICA = [
  278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556,
  556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667,
  556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556,
  556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722,
  500, 500, 500, 334, 260, 334, 584,
];
const HELVETICA_BOLD = [
  278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556,
  556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722,
  611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556,
  611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778,
  556, 556, 500, 389, 280, 389, 584,
];

function charWidth(ch: string, table: number[]): number {
  const code = ch.codePointAt(0)!;
  if (code >= 32 && code <= 126) return table[code - 32]!;
  // Acentuados medem como a letra base ("ç" = "c", "Ã" = "A").
  const base = ch.normalize('NFD').replace(/\p{M}/gu, '');
  if (base !== ch && base.length === 1) return charWidth(base, table);
  if (ch === '€') return 556;
  if (ch === '…') return 1000;
  if (ch === ' ') return 278;
  return 556;
}

export const afmMeasurer: TextMeasurer = {
  width(text, font) {
    const table = font.bold ? HELVETICA_BOLD : HELVETICA;
    let units = 0;
    for (const ch of text) units += charWidth(ch, table);
    return (units / 1000) * font.sizeMm;
  },
};
