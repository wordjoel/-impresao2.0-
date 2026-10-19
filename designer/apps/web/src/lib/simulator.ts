import { mmToDots, seededRandom, type ResolvedLabel } from '@athos-label/engine';
import { labelToSvg } from '@athos-label/exporters';
import { loadImage } from './persist';

export interface SimulatorOptions {
  dpi: number;
  /** Escurecimento (0-30): pontos vizinhos queimam junto, como com densidade alta. */
  darkness: number;
  /** % de pontos que falham (cabeça gasta, papel ruim). */
  noise: number;
  /** Área que a cabeça não alcança em cada borda (mm). */
  marginMm: number;
}

/**
 * Simulador de impressora térmica: rasteriza a etiqueta no DPI real (cada pixel = 1 dot),
 * binariza (térmica não tem cinza), aplica ganho de ponto e falhas, e marca a margem que a cabeça
 * não imprime. Mostra o que a impressora vai fazer com o desenho, antes de gastar etiqueta.
 */
export async function simulate(label: ResolvedLabel, o: SimulatorOptions): Promise<HTMLCanvasElement> {
  const w = mmToDots(label.widthMm, o.dpi);
  const h = mmToDots(label.heightMm, o.dpi);
  const svg = labelToSvg(label, { monochrome: true });
  const img = await loadImage(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`);

  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const c = canvas.getContext('2d', { willReadFrequently: true })!;
  c.imageSmoothingEnabled = false;
  c.drawImage(img, 0, 0, w, h);

  const src = c.getImageData(0, 0, w, h);
  const dark = new Uint8Array(w * h);
  const threshold = 128 + o.darkness * 3;
  for (let i = 0; i < w * h; i++) {
    const p = i * 4;
    const lum = 0.299 * src.data[p]! + 0.587 * src.data[p + 1]! + 0.114 * src.data[p + 2]!;
    dark[i] = lum < threshold ? 1 : 0;
  }

  const rnd = seededRandom(7);
  const margin = mmToDots(o.marginMm, o.dpi);
  const out = c.createImageData(w, h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      let on = dark[i] === 1;
      // Ganho de ponto horizontal (a cabeça aquece o vizinho) com densidade alta.
      if (!on && o.darkness > 15 && (dark[i - 1] === 1 || dark[i + 1] === 1) && rnd() < (o.darkness - 15) / 30) on = true;
      if (on && rnd() < o.noise / 100) on = false;
      const outside = x < margin || y < margin || x >= w - margin || y >= h - margin;
      if (outside) on = false;
      const p = i * 4;
      const v = on ? 20 : outside ? 236 : 252;
      out.data[p] = v;
      out.data[p + 1] = on ? 20 : outside ? 214 : 250;
      out.data[p + 2] = on ? 24 : outside ? 214 : 244;
      out.data[p + 3] = 255;
    }
  }
  c.putImageData(out, 0, 0);
  return canvas;
}
