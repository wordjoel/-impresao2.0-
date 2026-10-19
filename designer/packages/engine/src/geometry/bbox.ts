import { Vector2 } from './vector2';

/** Retângulo alinhado aos eixos (AABB). */
export interface BBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface RotatedRect extends BBox {
  /** Graus, em torno do canto superior esquerdo (mesma convenção do Konva e do ^FO do ZPL). */
  rotation: number;
}

export function corners(r: RotatedRect): Vector2[] {
  const origin = new Vector2(r.x, r.y);
  return [
    new Vector2(0, 0),
    new Vector2(r.width, 0),
    new Vector2(r.width, r.height),
    new Vector2(0, r.height),
  ].map((c) => origin.add(c.rotate(r.rotation)));
}

/** AABB que envolve o retângulo já rotacionado. */
export function boundsOf(r: RotatedRect): BBox {
  if (r.rotation % 360 === 0) return { x: r.x, y: r.y, width: r.width, height: r.height };
  return fromPoints(corners(r));
}

export function fromPoints(points: Vector2[]): BBox {
  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  const minX = Math.min(...xs);
  const minY = Math.min(...ys);
  return { x: minX, y: minY, width: Math.max(...xs) - minX, height: Math.max(...ys) - minY };
}

export function union(boxes: BBox[]): BBox | null {
  if (boxes.length === 0) return null;
  return fromPoints(
    boxes.flatMap((b) => [new Vector2(b.x, b.y), new Vector2(b.x + b.width, b.y + b.height)]),
  );
}

export function containsPoint(b: BBox, p: Vector2): boolean {
  return p.x >= b.x && p.x <= b.x + b.width && p.y >= b.y && p.y <= b.y + b.height;
}

export function intersects(a: BBox, b: BBox): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

/** true se `inner` cabe inteiro dentro de `outer` (ex.: objeto dentro da etiqueta). */
export function containsBox(outer: BBox, inner: BBox): boolean {
  return (
    inner.x >= outer.x &&
    inner.y >= outer.y &&
    inner.x + inner.width <= outer.x + outer.width &&
    inner.y + inner.height <= outer.y + outer.height
  );
}
