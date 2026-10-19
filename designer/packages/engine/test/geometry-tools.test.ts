import { describe, expect, it } from 'vitest';
import { align, distribute } from '../src/geometry/align';
import { smartGuides } from '../src/geometry/guides';
import { MonoBitmap } from '../src/render/mono';

const item = (id: string, x: number, y = 0, width = 10, height = 5, rotation = 0) => ({ id, x, y, width, height, rotation });
const label = { x: 0, y: 0, width: 50, height: 30 };

describe('alinhar e distribuir', () => {
  it('vários objetos alinham pela seleção; um só, pela etiqueta', () => {
    expect([...align([item('a', 5), item('b', 12, 8)], 'left', label)]).toEqual([
      ['a', { x: 5, y: 0 }],
      ['b', { x: 5, y: 8 }],
    ]);
    expect(align([item('a', 5)], 'right', label).get('a')).toEqual({ x: 40, y: 0 });
    expect(align([item('a', 5)], 'middle', label).get('a')).toEqual({ x: 5, y: 12.5 });
  });

  it('objeto girado alinha pela caixa visível', () => {
    // 90° em torno do canto: ocupa x de -5 a 0 relativo a x=10, então a borda visível é 5.
    expect(align([item('r', 10, 0, 20, 5, 90)], 'left', label).get('r')).toEqual({ x: 5, y: 0 });
  });

  it('distribui com espaço igual', () => {
    const d = distribute([item('a', 0), item('c', 50), item('b', 15)], 'horizontal');
    expect(d.get('b')).toEqual({ x: 25, y: 0 });
    expect(d.get('a')).toEqual({ x: 0, y: 0 });
    expect(d.get('c')).toEqual({ x: 50, y: 0 });
  });
});

describe('Smart Guides', () => {
  it('encosta na borda do vizinho e devolve a linha a desenhar', () => {
    const r = smartGuides({ x: 10.3, y: 0, width: 10, height: 5 }, [{ x: 10, y: 20, width: 5, height: 5 }], 0.5);
    expect(r.dx).toBeCloseTo(-0.3, 10);
    expect(r.dy).toBe(0);
    expect(r.guides).toEqual([{ orientation: 'vertical', position: 10, from: 0, to: 25 }]);
  });

  it('longe demais: nenhuma guia', () => {
    expect(smartGuides({ x: 13, y: 0, width: 10, height: 5 }, [{ x: 0, y: 10, width: 5, height: 5 }], 0.5).guides).toEqual([]);
  });
});

describe('bitmap 1 bit', () => {
  it('liga/desliga, gira, amplia, inverte e serializa', () => {
    const b = new MonoBitmap(2, 1);
    b.set(1, 0, true);
    expect([b.get(0, 0), b.get(1, 0)]).toEqual([false, true]);
    const r = b.rotate(90);
    expect([r.width, r.height, r.get(0, 1)]).toEqual([1, 2, true]);
    expect(b.resample(4, 2).get(3, 1)).toBe(true);
    expect(b.inverted()[0]).toBe(0b10111111);
    expect(MonoBitmap.fromData(b.toData()).data).toEqual(b.data);
    expect(b.toHex()).toBe('40');
  });

  it('RGBA: escuro vira preto, transparente vira branco', () => {
    const rgba = [0, 0, 0, 255, 0, 0, 0, 0];
    const m = MonoBitmap.fromRgba(rgba, 2, 1);
    expect([m.get(0, 0), m.get(1, 0)]).toEqual([true, false]);
  });

  it('matriz 2D ampliada por módulo', () => {
    const m = MonoBitmap.fromMatrix([1, 0, 0, 1], 2, 2, 3);
    expect([m.width, m.get(2, 2), m.get(3, 0), m.get(5, 5)]).toEqual([6, true, false, true]);
  });
});
