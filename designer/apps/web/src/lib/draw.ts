import type { ResolvedElement } from '@athos-label/engine';
import { layoutLines, runs } from '@athos-label/exporters';
import { cssFont } from './measure';

/** Imagens decodificadas por src; `onLoad` pede novo desenho quando chega uma imagem. */
const images = new Map<string, HTMLImageElement>();
let onImageLoad: () => void = () => {};
export function setImageLoadListener(fn: () => void) {
  onImageLoad = fn;
}

function image(src: string): HTMLImageElement | null {
  let img = images.get(src);
  if (!img) {
    img = new Image();
    img.onload = () => onImageLoad();
    img.src = src;
    images.set(src, img);
  }
  return img.complete && img.naturalWidth > 0 ? img : null;
}

/**
 * Desenha um elemento resolvido em coordenadas locais do objeto, em mm (o chamador já aplicou
 * posição, rotação e escala px/mm). Mesma geometria usada pelo SVG e pelo PDF.
 */
export function drawElement(c: CanvasRenderingContext2D, el: ResolvedElement): void {
  switch (el.kind) {
    case 'text': {
      c.fillStyle = el.color;
      c.font = cssFont(el, el.fontSizeMm);
      for (const l of layoutLines(el)) {
        c.textAlign = l.anchor === 'start' ? 'left' : l.anchor === 'middle' ? 'center' : 'right';
        c.fillText(l.text, l.anchorX, l.baseline);
      }
      if (el.overflow) {
        // Triângulo laranja no canto: o texto foi cortado (também aparece em Avisos).
        c.fillStyle = '#f79009';
        c.beginPath();
        c.moveTo(el.width, el.height);
        c.lineTo(el.width - 1.5, el.height);
        c.lineTo(el.width, el.height - 1.5);
        c.fill();
      }
      break;
    }
    case 'barcode1d': {
      c.fillStyle = '#000';
      for (const [s, len] of runs(el.modules)) c.fillRect(s * el.moduleMm, 0, len * el.moduleMm, el.barHeightMm);
      if (el.humanReadable) {
        c.font = cssFont({ family: 'Helvetica', bold: false }, el.textSizeMm);
        c.textAlign = 'center';
        c.fillText(el.humanText, el.barsWidthMm / 2, el.barHeightMm + el.textSizeMm);
      }
      break;
    }
    case 'barcode2d': {
      c.fillStyle = '#000';
      const rowMm = el.moduleMm * el.rowModules; // PDF417: linha mais alta que o módulo
      for (let r = 0; r < el.rows; r++) {
        for (const [s, len] of runs(el.matrix, r * el.cols, el.cols)) {
          c.fillRect(s * el.moduleMm, r * rowMm, len * el.moduleMm, rowMm);
        }
      }
      break;
    }
    case 'image': {
      const img = image(el.src);
      if (img) c.drawImage(img, 0, 0, el.width, el.height);
      else {
        c.fillStyle = '#eef0f3';
        c.fillRect(0, 0, el.width, el.height);
      }
      break;
    }
    case 'box': {
      if (el.filled) {
        c.fillStyle = el.color;
        c.fillRect(0, 0, el.width, el.height);
      } else {
        c.strokeStyle = el.color;
        c.lineWidth = el.borderMm;
        const b = el.borderMm / 2;
        c.strokeRect(b, b, Math.max(0, el.width - el.borderMm), Math.max(0, el.height - el.borderMm));
      }
      break;
    }
    case 'error': {
      c.fillStyle = 'rgba(217,45,32,0.08)';
      c.fillRect(0, 0, el.width, el.height);
      c.strokeStyle = '#d92d20';
      c.lineWidth = 0.25;
      c.setLineDash([0.8, 0.5]);
      c.strokeRect(0, 0, el.width, el.height);
      c.setLineDash([]);
      c.fillStyle = '#b42318';
      c.font = cssFont({ family: 'Helvetica', bold: true }, Math.min(2, el.height * 0.6));
      c.textAlign = 'left';
      c.fillText('⚠ ' + el.message.slice(0, 48), 0.6, Math.min(el.height - 0.4, 2.4));
      break;
    }
  }
}
