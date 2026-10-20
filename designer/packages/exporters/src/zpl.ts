import { MonoBitmap, RASTER_1D, RASTER_2D, type ResolvedElement } from '@athos-label/engine';
import { dots, layoutLines, localRectBounds, quarterTurn, raster1D, raster2D, rotationIssue } from './common';
import type { CommandGenerator, ElementContext } from './printer';

/**
 * ZPL II (Zebra e compatíveis).
 *  - Posição: ^FO no canto superior esquerdo da caixa VISÍVEL (já girada).
 *  - Texto: ^FH_ + escape hexa de ^ ~ _ \ (ZPL não tem "^^"; esse escape do plano original é
 *    inválido e quebraria o comando). ^CI28 = UTF-8, acentos passam direto.
 *  - Código de barras: módulo ^BY em dots inteiros, escolhido pelo resolver.
 *  - Cópias: ^PQ (a impressora repete; o envio fica com o tamanho de uma etiqueta).
 * O que depende de hardware (fonte residente 0 mais condensada que Helvetica, deslocamento do
 * ^BQ) precisa da validação física, como no Print Bridge.
 */

const ORIENT = ['N', 'R', 'I', 'B'] as const;

export function zplEscape(s: string): string {
  return s.replace(/[\^~_\\]/g, (c) => `_${c.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')}`);
}

const fo = (x: number, y: number) => `^FO${Math.max(0, x)},${Math.max(0, y)}`;

function graphic(x: number, y: number, bmp: MonoBitmap): string {
  const total = bmp.data.length;
  return `${fo(x, y)}^GFA,${total},${total},${bmp.bytesPerRow},${bmp.toHex()}^FS\n`;
}

export const zpl: CommandGenerator = {
  language: 'ZPL',

  header(widthMm, heightMm, dpi) {
    return `^XA\n^CI28\n^PW${dots(widthMm, dpi)}\n^LL${dots(heightMm, dpi)}\n^LH0,0\n`;
  },

  footer(copies) {
    return `${copies > 1 ? `^PQ${copies}\n` : ''}^XZ\n`;
  },

  encode(commands) {
    return new TextEncoder().encode(commands);
  },

  element(el: ResolvedElement, ctx: ElementContext): string {
    const { dpi } = ctx;
    const rot = quarterTurn(el.rotation);
    if (!rot.exact && el.kind !== 'error') ctx.issues.push(rotationIssue(el.id, el.rotation, rot.degrees));
    const o = ORIENT[rot.q];
    const D = (mm: number) => dots(mm, dpi);

    switch (el.kind) {
      case 'text': {
        const bmp = ctx.rasterizeText?.(el, dpi);
        if (bmp) {
          const b = localRectBounds(el, rot.degrees, 0, 0, el.width, el.height);
          return graphic(D(b.x), D(b.y), bmp.rotate(rot.degrees));
        }
        const h = Math.max(1, D(el.fontSizeMm));
        const just = el.align === 'LEFT' ? 'L' : el.align === 'CENTER' ? 'C' : 'R';
        return layoutLines(el)
          .filter((l) => l.text !== '')
          .map((l) => {
            const b = localRectBounds(el, rot.degrees, 0, l.top, el.width, el.lineHeightMm);
            // ^FB de 1 linha com a largura do objeto: a impressora alinha; a quebra já veio do resolver.
            return `${fo(D(b.x), D(b.y))}^A0${o},${h},${h}^FB${D(el.width)},1,0,${just},0^FH_^FD${zplEscape(l.text)}^FS\n`;
          })
          .join('');
      }
      case 'barcode1d': {
        if (RASTER_1D.has(el.symbology)) {
          const r = raster1D(el, dpi);
          return graphic(D(r.barsAt.x), D(r.barsAt.y), r.bars) + (r.text ? this.element(r.text, ctx) : '');
        }
        const b = localRectBounds(el, rot.degrees, 0, 0, el.barsWidthMm, el.height);
        const h = Math.max(1, D(el.barHeightMm));
        const hr = el.humanReadable ? 'Y' : 'N';
        const cmd = {
          CODE128: `^BC${o},${h},${hr},N,N,A`,
          EAN13: `^BE${o},${h},${hr},N`,
          UPCA: `^BU${o},${h},${hr},N,Y`,
          CODE39: `^B3${o},N,${h},${hr},N`,
          EAN8: `^B8${o},${h},${hr},N`,
        }[el.symbology as 'CODE128' | 'EAN13' | 'UPCA' | 'CODE39' | 'EAN8'];
        return `${fo(D(b.x), D(b.y))}^BY${el.moduleDots},3,${h}${cmd}^FH_^FD${zplEscape(el.printerData)}^FS\n`;
      }
      case 'barcode2d': {
        if (RASTER_2D.has(el.symbology)) {
          const r = raster2D(el);
          return graphic(D(r.at.x), D(r.at.y), r.bmp);
        }
        // QR/DataMatrix leem em qualquer orientação: vão sem giro, no canto da caixa visível.
        const size = el.moduleMm * el.symbolModules;
        const b = localRectBounds(el, rot.degrees, 0, 0, size, size);
        if (el.symbology === 'QR') {
          return `${fo(D(b.x), D(b.y))}^BQN,2,${el.moduleDots}^FH_^FD${el.ecLevel}A,${zplEscape(el.data)}^FS\n`;
        }
        return `${fo(D(b.x), D(b.y))}^BXN,${el.moduleDots},200,${el.cols},${el.rows}^FH_^FD${zplEscape(el.data)}^FS\n`;
      }
      case 'image': {
        if (!el.mono) return '';
        const b = localRectBounds(el, rot.degrees, 0, 0, el.width, el.height);
        const bmp = MonoBitmap.fromData(el.mono).resample(D(el.width), D(el.height)).rotate(rot.degrees);
        return graphic(D(b.x), D(b.y), bmp);
      }
      case 'box': {
        const b = localRectBounds(el, rot.degrees, 0, 0, el.width, el.height);
        const w = Math.max(1, D(b.width));
        const h = Math.max(1, D(b.height));
        const t = el.filled ? Math.min(w, h) : Math.max(1, D(el.borderMm));
        return `${fo(D(b.x), D(b.y))}^GB${w},${h},${t},B,0^FS\n`;
      }
      case 'error':
        return ''; // já reportado pelo resolver; não imprime lixo no lugar
    }
  },
};
