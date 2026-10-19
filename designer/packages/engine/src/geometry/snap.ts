/** Passo do grid magnético em mm. 0 desliga o snap. */
export type GridStep = 0 | 0.5 | 1 | 2 | 5;

/**
 * Encaixa no múltiplo mais próximo de `step`.
 * O arredondamento final remove lixo de ponto flutuante (0.1 + 0.2 => 0.30000000000000004).
 */
export function snapToGrid(value: number, step: number): number {
  if (step <= 0) return value;
  const snapped = Math.round(value / step) * step;
  return Math.round(snapped * 1e6) / 1e6;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}
