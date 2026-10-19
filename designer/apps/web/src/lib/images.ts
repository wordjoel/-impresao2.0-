import { MonoBitmap, type MonoBitmapData } from '@athos-label/engine';
import { loadImage } from './persist';

/** Lado máximo do bitmap guardado no template (o exportador reamostra para o tamanho na etiqueta). */
const MAX_SIDE = 800;

/** Converte a imagem importada em 1 bit (limiar de luminância), guardada junto do objeto. */
export async function toMono(src: string, threshold = 128): Promise<{ mono: MonoBitmapData; aspect: number }> {
  const img = await loadImage(src);
  const scale = Math.min(1, MAX_SIDE / Math.max(img.naturalWidth, img.naturalHeight));
  const w = Math.max(1, Math.round(img.naturalWidth * scale));
  const h = Math.max(1, Math.round(img.naturalHeight * scale));
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const c = canvas.getContext('2d')!;
  c.drawImage(img, 0, 0, w, h);
  const mono = MonoBitmap.fromRgba(c.getImageData(0, 0, w, h).data, w, h, threshold);
  return { mono: mono.toData(), aspect: img.naturalWidth / img.naturalHeight };
}
