import type { MonoBitmapData } from '../model/objects';

/**
 * Bitmap de 1 bit por pixel, linhas alinhadas a byte, bit mais significativo à esquerda,
 * bit 1 = preto (convenção do ^GFA do ZPL; TSPL e EPL usam o inverso, ver `inverted`).
 */
export class MonoBitmap {
  readonly bytesPerRow: number;

  constructor(
    readonly width: number,
    readonly height: number,
    readonly data: Uint8Array = new Uint8Array(((width + 7) >> 3) * height),
  ) {
    this.bytesPerRow = (width + 7) >> 3;
    if (width <= 0 || height <= 0) throw new Error(`Bitmap vazio ${width}x${height}`);
    if (data.length !== this.bytesPerRow * height) throw new Error('Tamanho de dados incompatível com o bitmap');
  }

  get(x: number, y: number): boolean {
    return ((this.data[y * this.bytesPerRow + (x >> 3)]! >> (7 - (x & 7))) & 1) === 1;
  }

  set(x: number, y: number, black: boolean): void {
    const i = y * this.bytesPerRow + (x >> 3);
    const bit = 0x80 >> (x & 7);
    this.data[i] = black ? this.data[i]! | bit : this.data[i]! & ~bit;
  }

  /** Vizinho mais próximo: preserva bordas duras (imagem térmica não tem cinza). */
  resample(w: number, h: number): MonoBitmap {
    const out = new MonoBitmap(Math.max(1, w), Math.max(1, h));
    for (let y = 0; y < out.height; y++) {
      const sy = Math.min(this.height - 1, Math.floor((y * this.height) / out.height));
      for (let x = 0; x < out.width; x++) {
        if (this.get(Math.min(this.width - 1, Math.floor((x * this.width) / out.width)), sy)) out.set(x, y, true);
      }
    }
    return out;
  }

  /** Rotação horária em múltiplos de 90°. */
  rotate(degrees: number): MonoBitmap {
    const q = ((Math.round(degrees / 90) % 4) + 4) % 4;
    if (q === 0) return this;
    const swap = q % 2 === 1;
    const out = new MonoBitmap(swap ? this.height : this.width, swap ? this.width : this.height);
    for (let y = 0; y < this.height; y++) {
      for (let x = 0; x < this.width; x++) {
        if (!this.get(x, y)) continue;
        if (q === 1) out.set(this.height - 1 - y, x, true);
        else if (q === 2) out.set(this.width - 1 - x, this.height - 1 - y, true);
        else out.set(y, this.width - 1 - x, true);
      }
    }
    return out;
  }

  /** Bytes com bits invertidos (TSPL BITMAP e EPL GW: bit 0 queima o ponto). */
  inverted(): Uint8Array {
    return this.data.map((b) => b ^ 0xff);
  }

  toHex(): string {
    let s = '';
    for (const b of this.data) s += HEX[b >> 4]! + HEX[b & 15]!;
    return s;
  }

  toData(): MonoBitmapData {
    let bin = '';
    for (const b of this.data) bin += String.fromCharCode(b);
    return { width: this.width, height: this.height, data: btoa(bin) };
  }

  static fromData(d: MonoBitmapData): MonoBitmap {
    const bin = atob(d.data);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new MonoBitmap(d.width, d.height, bytes);
  }

  /** De RGBA (canvas/ImageData): luminância abaixo do limiar = preto; transparente = branco. */
  static fromRgba(rgba: ArrayLike<number>, width: number, height: number, threshold = 128): MonoBitmap {
    const out = new MonoBitmap(width, height);
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const i = (y * width + x) * 4;
        const a = rgba[i + 3]! / 255;
        // Compõe sobre fundo branco antes do limiar.
        const lum = (0.299 * rgba[i]! + 0.587 * rgba[i + 1]! + 0.114 * rgba[i + 2]!) * a + 255 * (1 - a);
        if (lum < threshold) out.set(x, y, true);
      }
    }
    return out;
  }

  /** Matriz de módulos (código 2D) ampliada para `module` dots por módulo. */
  static fromMatrix(matrix: ArrayLike<number>, rows: number, cols: number, module: number): MonoBitmap {
    const out = new MonoBitmap(cols * module, rows * module);
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        if (!matrix[r * cols + c]) continue;
        for (let dy = 0; dy < module; dy++) for (let dx = 0; dx < module; dx++) out.set(c * module + dx, r * module + dy, true);
      }
    }
    return out;
  }
}

const HEX = '0123456789ABCDEF';
