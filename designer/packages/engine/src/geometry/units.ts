export const MM_PER_INCH = 25.4;

/** DPIs comuns de impressoras térmicas (203 = 8 dots/mm, 300 ≈ 12, 600 ≈ 24). */
export type PrinterDpi = 203 | 300 | 600;

/**
 * mm -> dots da impressora. Arredonda para o dot inteiro mais próximo,
 * porque a cabeça de impressão não endereça meio dot.
 */
export function mmToDots(mm: number, dpi: number): number {
  return Math.round((mm * dpi) / MM_PER_INCH);
}

export function dotsToMm(dots: number, dpi: number): number {
  return (dots * MM_PER_INCH) / dpi;
}

/** Fator de tela: quantos pixels CSS representam 1 mm no zoom atual. */
export function pxPerMm(zoom: number, screenDpi = 96): number {
  return (screenDpi / MM_PER_INCH) * zoom;
}
