import type { BBox } from './bbox';

export interface Guide {
  orientation: 'vertical' | 'horizontal';
  /** Posição da linha em mm (x para vertical, y para horizontal). */
  position: number;
  /** Extensão da linha, cobrindo os dois objetos alinhados. */
  from: number;
  to: number;
}

export interface GuideResult {
  /** Correção a somar na posição do objeto arrastado para encostar na guia. */
  dx: number;
  dy: number;
  guides: Guide[];
}

const edges = (b: BBox, h: boolean) =>
  h ? [b.x, b.x + b.width / 2, b.x + b.width] : [b.y, b.y + b.height / 2, b.y + b.height];

/**
 * Smart Guides: compara bordas e centros da caixa arrastada com as dos outros objetos
 * (e da etiqueta). No eixo em que houver encaixe dentro de `threshold` mm, devolve a correção
 * e as linhas a desenhar. Quando não há encaixe, o grid continua valendo.
 */
export function smartGuides(moving: BBox, others: readonly BBox[], threshold: number): GuideResult {
  const result: GuideResult = { dx: 0, dy: 0, guides: [] };

  for (const h of [true, false]) {
    const mine = edges(moving, h);
    let best: { delta: number; target: number } | null = null;
    for (const other of others) {
      for (const t of edges(other, h)) {
        for (const m of mine) {
          const delta = t - m;
          if (Math.abs(delta) <= threshold && (!best || Math.abs(delta) < Math.abs(best.delta))) {
            best = { delta, target: t };
          }
        }
      }
    }
    if (!best) continue;
    if (h) result.dx = best.delta;
    else result.dy = best.delta;

    // Uma linha por objeto que compartilha a coordenada alvo, depois do encaixe.
    const snapped: BBox = h ? { ...moving, x: moving.x + best.delta } : { ...moving, y: moving.y + best.delta };
    for (const other of others) {
      if (!edges(other, h).some((t) => Math.abs(t - best!.target) < 1e-6)) continue;
      const lo = h ? Math.min(snapped.y, other.y) : Math.min(snapped.x, other.x);
      const hi = h
        ? Math.max(snapped.y + snapped.height, other.y + other.height)
        : Math.max(snapped.x + snapped.width, other.x + other.width);
      result.guides.push({ orientation: h ? 'vertical' : 'horizontal', position: best.target, from: lo, to: hi });
    }
  }
  return result;
}
