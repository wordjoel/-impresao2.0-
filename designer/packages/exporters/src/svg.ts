import type { ResolvedElement, ResolvedLabel } from '@athos-label/engine';
import { layoutLines, runs } from './common';

/**
 * SVG vetorial em mm (viewBox = tamanho físico). Serve para e-commerce, catálogo, prévia e para o
 * simulador de impressora (que rasteriza este SVG no DPI da impressora).
 */
export interface SvgOptions {
  /** Desenha objetos com erro como caixa vermelha tracejada (prévia); na exportação, omite. */
  showErrors?: boolean;
  /** Tudo em preto (simulador térmico). */
  monochrome?: boolean;
  background?: string;
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const n = (v: number) => String(Math.round(v * 10000) / 10000);

function elementSvg(el: ResolvedElement, o: SvgOptions): string {
  const color = (c: string) => (o.monochrome ? '#000' : c);
  const inner = (() => {
    switch (el.kind) {
      case 'text':
        return layoutLines(el)
          .map(
            (l) =>
              `<text x="${n(l.anchorX)}" y="${n(l.baseline)}" text-anchor="${l.anchor}" font-family="${esc(el.family)}, Helvetica, Arial, sans-serif" font-size="${n(el.fontSizeMm)}"${el.bold ? ' font-weight="bold"' : ''}${el.italic ? ' font-style="italic"' : ''} fill="${color(el.color)}" xml:space="preserve">${esc(l.text)}</text>`,
          )
          .join('');
      case 'barcode1d': {
        const bars = runs(el.modules)
          .map(([s, len]) => `<rect x="${n(s * el.moduleMm)}" y="0" width="${n(len * el.moduleMm)}" height="${n(el.barHeightMm)}"/>`)
          .join('');
        const text = el.humanReadable
          ? `<text x="${n(el.barsWidthMm / 2)}" y="${n(el.barHeightMm + el.textSizeMm)}" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="${n(el.textSizeMm)}">${esc(el.humanText)}</text>`
          : '';
        return `<g fill="#000">${bars}${text}</g>`;
      }
      case 'barcode2d': {
        let rects = '';
        const rowMm = el.moduleMm * el.rowModules;
        for (let r = 0; r < el.rows; r++) {
          for (const [s, len] of runs(el.matrix, r * el.cols, el.cols)) {
            rects += `<rect x="${n(s * el.moduleMm)}" y="${n(r * rowMm)}" width="${n(len * el.moduleMm)}" height="${n(rowMm)}"/>`;
          }
        }
        return `<g fill="#000" shape-rendering="crispEdges">${rects}</g>`;
      }
      case 'image':
        return `<image href="${esc(el.src)}" x="0" y="0" width="${n(el.width)}" height="${n(el.height)}" preserveAspectRatio="none"/>`;
      case 'box': {
        if (el.filled) return `<rect width="${n(el.width)}" height="${n(el.height)}" fill="${color(el.color)}"/>`;
        const b = el.borderMm;
        return `<rect x="${n(b / 2)}" y="${n(b / 2)}" width="${n(Math.max(0, el.width - b))}" height="${n(Math.max(0, el.height - b))}" fill="none" stroke="${color(el.color)}" stroke-width="${n(b)}"/>`;
      }
      case 'error':
        return o.showErrors
          ? `<rect width="${n(el.width)}" height="${n(el.height)}" fill="#fde8e8" stroke="#d92d20" stroke-width="0.3" stroke-dasharray="1 0.6"/><text x="1" y="${n(Math.min(el.height - 0.5, 3))}" font-size="2" font-family="Helvetica, Arial, sans-serif" fill="#b42318">${esc(el.message.slice(0, 60))}</text>`
          : '';
    }
  })();
  if (!inner) return '';
  return `<g transform="translate(${n(el.x)} ${n(el.y)}) rotate(${n(el.rotation)})">${inner}</g>`;
}

export function labelToSvg(label: ResolvedLabel, o: SvgOptions = {}): string {
  const w = n(label.widthMm);
  const h = n(label.heightMm);
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}mm" height="${h}mm" viewBox="0 0 ${w} ${h}">` +
    `<rect width="${w}" height="${h}" fill="${o.background ?? '#fff'}"/>` +
    label.elements.map((el) => elementSvg(el, o)).join('') +
    `</svg>`
  );
}
