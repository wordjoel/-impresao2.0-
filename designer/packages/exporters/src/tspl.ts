import { MonoBitmap, RASTER_1D, type ResolvedElement } from '@athos-label/engine';
import { bytesToLatin1, dots, latin1Bytes, layoutLines, localRectBounds, quarterTurn, raster1D, rotationIssue, toCp1252, toLabel } from './common';
import type { CommandGenerator, ElementContext } from './printer';

/**
 * TSPL/TSPL2 (TSC — a MB241 do Carrefour). Regras herdadas do Print Bridge validado:
 *  - payload de 1 byte por caractere (o Bridge Android envia TSPL em ISO-8859-1);
 *  - BITMAP com bits INVERTIDOS (na TSC o bit 0 queima o ponto);
 *  - SIZE sem GAP/DIRECTION, para não desfazer a calibração feita na impressora.
 * Texto: fonte residente "0" (escalável), tamanho em pontos; BLOCK cuida do alinhamento.
 * Rotação em TSPL gira em torno do ponto informado (não do canto da caixa visível como no ZPL).
 */

export function tsplQuote(s: string, onLoss?: (ch: string) => void): string {
  return `"${toCp1252(s, onLoss).replace(/"/g, '\\["]')}"`;
}

const ALIGN = { LEFT: 1, CENTER: 2, RIGHT: 3 } as const;

export const tspl: CommandGenerator = {
  language: 'TSPL',

  header(widthMm, heightMm) {
    return `SIZE ${round1(widthMm)} mm,${round1(heightMm)} mm\r\nCODEPAGE 1252\r\nCLS\r\n`;
  },

  footer(copies) {
    return `PRINT 1,${Math.max(1, copies)}\r\n`;
  },

  encode(commands) {
    return latin1Bytes(commands);
  },

  element(el: ResolvedElement, ctx: ElementContext): string {
    const { dpi } = ctx;
    const rot = quarterTurn(el.rotation);
    if (!rot.exact && el.kind !== 'error') ctx.issues.push(rotationIssue(el.id, el.rotation, rot.degrees));
    const D = (mm: number) => dots(mm, dpi);
    const at = (lx: number, ly: number) => {
      const p = toLabel(el.x, el.y, lx, ly, rot.degrees);
      return `${Math.max(0, D(p.x))},${Math.max(0, D(p.y))}`;
    };
    let lost = false;
    const onLoss = () => {
      if (!lost) ctx.issues.push({ severity: 'warning', code: 'UNSUPPORTED', message: 'Caractere fora do Windows-1252 trocado por "?"', objectId: el.id });
      lost = true;
    };

    switch (el.kind) {
      case 'text': {
        const bmp = ctx.rasterizeText?.(el, dpi);
        if (bmp) return bitmap(localRectBounds(el, rot.degrees, 0, 0, el.width, el.height), bmp.rotate(rot.degrees), D);
        // Fonte "0": multiplicadores = tamanho em pontos (1 pt = 1/72 pol).
        const pt = Math.max(1, Math.round((el.fontSizeMm * 72) / 25.4));
        return layoutLines(el)
          .filter((l) => l.text !== '')
          .map(
            (l) =>
              `BLOCK ${at(0, l.top)},${D(el.width)},${Math.max(1, D(el.lineHeightMm))},"0",${rot.degrees},${pt},${pt},0,${ALIGN[el.align]},${tsplQuote(l.text, onLoss)}\r\n`,
          )
          .join('');
      }
      case 'barcode1d': {
        if (RASTER_1D.has(el.symbology)) {
          const r = raster1D(el, dpi);
          return bitmap(r.barsAt, r.bars, D) + (r.text ? this.element(r.text, ctx) : '');
        }
        const code = { CODE128: '128', EAN13: 'EAN13', UPCA: 'UPCA', CODE39: '39', EAN8: 'EAN8' }[
          el.symbology as 'CODE128' | 'EAN13' | 'UPCA' | 'CODE39' | 'EAN8'
        ];
        const m = el.moduleDots;
        const wide = el.symbology === 'CODE39' ? m * 3 : m;
        return `BARCODE ${at(0, 0)},"${code}",${Math.max(1, D(el.barHeightMm))},${el.humanReadable ? 1 : 0},${rot.degrees},${m},${wide},${tsplQuote(el.printerData)}\r\n`;
      }
      case 'barcode2d': {
        const size = el.moduleMm * el.symbolModules;
        const b = localRectBounds(el, rot.degrees, 0, 0, size, size);
        if (el.symbology === 'QR') {
          return `QRCODE ${D(b.x)},${D(b.y)},${el.ecLevel},${el.moduleDots},A,0,${tsplQuote(el.data, onLoss)}\r\n`;
        }
        const px = el.moduleDots * el.symbolModules;
        return `DMATRIX ${D(b.x)},${D(b.y)},${px},${px},x${el.moduleDots},${el.rows},${el.cols},${tsplQuote(el.data, onLoss)}\r\n`;
      }
      case 'image': {
        if (!el.mono) return '';
        const bmp = MonoBitmap.fromData(el.mono).resample(D(el.width), D(el.height)).rotate(rot.degrees);
        return bitmap(localRectBounds(el, rot.degrees, 0, 0, el.width, el.height), bmp, D);
      }
      case 'box': {
        const b = localRectBounds(el, rot.degrees, 0, 0, el.width, el.height);
        const [x, y, w, h] = [D(b.x), D(b.y), Math.max(1, D(b.width)), Math.max(1, D(b.height))];
        if (el.filled) return `BAR ${x},${y},${w},${h}\r\n`;
        return `BOX ${x},${y},${x + w},${y + h},${Math.max(1, D(el.borderMm))}\r\n`;
      }
      case 'error':
        return '';
    }
  },
};

function bitmap(b: { x: number; y: number }, bmp: MonoBitmap, D: (mm: number) => number): string {
  return `BITMAP ${Math.max(0, D(b.x))},${Math.max(0, D(b.y))},${bmp.bytesPerRow},${bmp.height},0,${bytesToLatin1(bmp.inverted())}\r\n`;
}

const round1 = (v: number) => Math.round(v * 10) / 10;
