import { boundsOf, union, type BBox, type RotatedRect } from './bbox';

export type AlignMode = 'left' | 'centerH' | 'right' | 'top' | 'middle' | 'bottom';

export interface Placed extends RotatedRect {
  id: string;
}

export type MovePatch = Map<string, { x: number; y: number }>;

const round = (v: number) => Math.round(v * 1e6) / 1e6;

/**
 * Alinha pela caixa visível (AABB já rotacionada) de cada objeto. Com um objeto só, a referência
 * é a etiqueta (`container`); com vários, a caixa que envolve a seleção — como no Figma.
 */
export function align(items: readonly Placed[], mode: AlignMode, container: BBox): MovePatch {
  const boxes = items.map((i) => ({ item: i, box: boundsOf(i) }));
  const ref = items.length === 1 ? container : union(boxes.map((b) => b.box))!;
  const out: MovePatch = new Map();
  for (const { item, box } of boxes) {
    let dx = 0;
    let dy = 0;
    if (mode === 'left') dx = ref.x - box.x;
    if (mode === 'right') dx = ref.x + ref.width - (box.x + box.width);
    if (mode === 'centerH') dx = ref.x + ref.width / 2 - (box.x + box.width / 2);
    if (mode === 'top') dy = ref.y - box.y;
    if (mode === 'bottom') dy = ref.y + ref.height - (box.y + box.height);
    if (mode === 'middle') dy = ref.y + ref.height / 2 - (box.y + box.height / 2);
    out.set(item.id, { x: round(item.x + dx), y: round(item.y + dy) });
  }
  return out;
}

/** Espaçamento igual entre as caixas, mantendo fixos o primeiro e o último. Exige 3+ objetos. */
export function distribute(items: readonly Placed[], axis: 'horizontal' | 'vertical'): MovePatch {
  const out: MovePatch = new Map();
  if (items.length < 3) return out;
  const h = axis === 'horizontal';
  const boxes = items
    .map((i) => ({ item: i, box: boundsOf(i) }))
    .sort((a, b) => (h ? a.box.x - b.box.x : a.box.y - b.box.y));
  const first = boxes[0]!.box;
  const last = boxes[boxes.length - 1]!.box;
  const span = h ? last.x + last.width - first.x : last.y + last.height - first.y;
  const occupied = boxes.reduce((s, b) => s + (h ? b.box.width : b.box.height), 0);
  const gap = (span - occupied) / (boxes.length - 1);
  let cursor = h ? first.x : first.y;
  for (const { item, box } of boxes) {
    const delta = cursor - (h ? box.x : box.y);
    out.set(item.id, { x: round(item.x + (h ? delta : 0)), y: round(item.y + (h ? 0 : delta)) });
    cursor += (h ? box.width : box.height) + gap;
  }
  return out;
}
