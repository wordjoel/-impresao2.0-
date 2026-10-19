import { describe, expect, it } from 'vitest';
import { Vector2 } from '../src/geometry/vector2';
import { boundsOf, containsBox, containsPoint, intersects, union } from '../src/geometry/bbox';
import { snapToGrid } from '../src/geometry/snap';

describe('Vector2', () => {
  it('operações básicas', () => {
    const v = new Vector2(3, 4);
    expect(v.length()).toBe(5);
    expect(v.add(new Vector2(1, 1))).toEqual(new Vector2(4, 5));
    expect(v.sub(new Vector2(1, 1))).toEqual(new Vector2(2, 3));
    expect(v.scale(2)).toEqual(new Vector2(6, 8));
  });

  it('rotate 90° é horário com Y para baixo', () => {
    expect(new Vector2(1, 0).rotate(90).equals(new Vector2(0, 1))).toBe(true);
    expect(new Vector2(1, 0).rotate(180).equals(new Vector2(-1, 0))).toBe(true);
  });
});

describe('BBox', () => {
  it('boundsOf sem rotação devolve o próprio retângulo', () => {
    expect(boundsOf({ x: 1, y: 2, width: 10, height: 5, rotation: 0 })).toEqual({
      x: 1,
      y: 2,
      width: 10,
      height: 5,
    });
  });

  it('boundsOf a 90° gira em torno do canto superior esquerdo', () => {
    const b = boundsOf({ x: 10, y: 10, width: 20, height: 5, rotation: 90 });
    expect(b.x).toBeCloseTo(5);
    expect(b.y).toBeCloseTo(10);
    expect(b.width).toBeCloseTo(5);
    expect(b.height).toBeCloseTo(20);
  });

  it('union, intersects, contains', () => {
    const a = { x: 0, y: 0, width: 10, height: 10 };
    const b = { x: 5, y: 5, width: 10, height: 10 };
    const c = { x: 20, y: 20, width: 1, height: 1 };
    expect(union([a, b])).toEqual({ x: 0, y: 0, width: 15, height: 15 });
    expect(union([])).toBeNull();
    expect(intersects(a, b)).toBe(true);
    expect(intersects(a, c)).toBe(false);
    // encostar na borda não é sobreposição
    expect(intersects(a, { x: 10, y: 0, width: 5, height: 5 })).toBe(false);
    expect(containsPoint(a, new Vector2(10, 10))).toBe(true);
    expect(containsBox({ x: 0, y: 0, width: 50, height: 30 }, a)).toBe(true);
    expect(containsBox(a, b)).toBe(false);
  });
});

describe('snapToGrid', () => {
  it('passo de 1 mm', () => {
    expect(snapToGrid(3.4, 1)).toBe(3);
    expect(snapToGrid(3.6, 1)).toBe(4);
  });

  it('passo de 0.5 mm sem lixo de float', () => {
    expect(snapToGrid(3.3, 0.5)).toBe(3.5);
    expect(snapToGrid(0.1 + 0.2, 0.1)).toBe(0.3);
  });

  it('passo 0 desliga', () => {
    expect(snapToGrid(3.33, 0)).toBe(3.33);
  });
});
