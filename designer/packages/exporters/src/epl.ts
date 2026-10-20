import { MonoBitmap, RASTER_1D, RASTER_2D, type ResolvedElement } from '@athos-label/engine';
import {
  bytesToLatin1,
  dots,
  latin1Bytes,
  layoutLines,
  localRectBounds,
  quarterTurn,
  raster1D,
  raster2D,
  rotationIssue,
  toCp1252,
  toLabel,
} from './common';
import type { CommandGenerator, ElementContext } from './printer';

/**
 * EPL2 (Eltron / Zebra legado). Fontes residentes são bitmap de largura fixa (1 a 5), então
 * o alinhamento é calculado aqui com exatidão. QR/DataMatrix variam muito entre firmwares
 * EPL; por isso vão como gráfico (GW), que toda EPL2 imprime igual.
 * Sem validação em hardware até agora (não há impressora EPL no projeto).
 */

/** Células das fontes residentes (largura x altura em dots), por DPI. */
const FONTS: Record<number, [number, number][]> = {
  203: [[8, 12], [10, 16], [12, 20], [14, 24], [32, 48]],
  300: [[12, 20], [16, 28], [20, 36], [24, 44], [48, 80]],
};

/**
 * Fonte e multiplicador cuja altura fica mais perto do alvo. No empate, o menor multiplicador:
 * fonte nativa maior sai nítida; fonte pequena ampliada sai serrilhada.
 */
export function pickEplFont(targetDots: number, dpi: number): { font: number; mult: number; cellW: number; cellH: number } {
  const table = FONTS[dpi] ?? FONTS[203]!;
  let best = { font: 1, mult: 1, cellW: table[0]![0], cellH: table[0]![1], err: Infinity };
  table.forEach(([w, h], i) => {
    for (let mult = 1; mult <= 9; mult++) {
      const err = Math.abs(h * mult - targetDots);
      if (err < best.err || (err === best.err && mult < best.mult)) best = { font: i + 1, mult, cellW: w, cellH: h, err };
    }
  });
  return { font: best.font, mult: best.mult, cellW: best.cellW, cellH: best.cellH };
}

const quote = (s: string, onLoss?: (ch: string) => void) => `"${toCp1252(s, onLoss).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

export const epl: CommandGenerator = {
  language: 'EPL',

  header(widthMm, _heightMm, dpi) {
    // Sem Q (comprimento/gap): usa a calibração da impressora, como o TSPL sem GAP.
    return `\nN\nI8,A,001\nq${dots(widthMm, dpi)}\n`;
  },

  footer(copies) {
    return `P${Math.max(1, copies)}\n`;
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
    const gw = (b: { x: number; y: number }, bmp: MonoBitmap) =>
      `GW${Math.max(0, D(b.x))},${Math.max(0, D(b.y))},${bmp.bytesPerRow},${bmp.height},${bytesToLatin1(bmp.inverted())}\n`;
    let lost = false;
    const onLoss = () => {
      if (!lost) ctx.issues.push({ severity: 'warning', code: 'UNSUPPORTED', message: 'Caractere fora do Windows-1252 trocado por "?"', objectId: el.id });
      lost = true;
    };

    switch (el.kind) {
      case 'text': {
        const bmp = ctx.rasterizeText?.(el, dpi);
        if (bmp) return gw(localRectBounds(el, rot.degrees, 0, 0, el.width, el.height), bmp.rotate(rot.degrees));
        const f = pickEplFont(D(el.fontSizeMm), dpi);
        return layoutLines(el)
          .filter((l) => l.text !== '')
          .map((l) => {
            const text = toCp1252(l.text, onLoss);
            const lineDots = text.length * f.cellW * f.mult;
            const free = Math.max(0, D(el.width) - lineDots);
            const dx = el.align === 'LEFT' ? 0 : el.align === 'CENTER' ? free / 2 : free;
            const dy = (D(el.lineHeightMm) - f.cellH * f.mult) / 2;
            return `A${at((dx * 25.4) / dpi, l.top + (Math.max(0, dy) * 25.4) / dpi)},${rot.q},${f.font},${f.mult},${f.mult},N,${quote(l.text)}\n`;
          })
          .join('');
      }
      case 'barcode1d': {
        if (RASTER_1D.has(el.symbology)) {
          const r = raster1D(el, dpi);
          return gw(r.barsAt, r.bars) + (r.text ? this.element(r.text, ctx) : '');
        }
        const type = { CODE128: '1', EAN13: 'E30', UPCA: 'UA0', CODE39: '3', EAN8: 'E80' }[
          el.symbology as 'CODE128' | 'EAN13' | 'UPCA' | 'CODE39' | 'EAN8'
        ];
        const m = el.moduleDots;
        const wide = el.symbology === 'CODE39' ? m * 3 : m * 2;
        return `B${at(0, 0)},${rot.q},${type},${m},${wide},${Math.max(1, D(el.barHeightMm))},${el.humanReadable ? 'B' : 'N'},${quote(el.printerData)}\n`;
      }
      case 'barcode2d': {
        if (RASTER_2D.has(el.symbology)) {
          const r = raster2D(el);
          return gw(r.at, r.bmp);
        }
        const size = el.moduleMm * el.symbolModules;
        return gw(localRectBounds(el, rot.degrees, 0, 0, size, size), MonoBitmap.fromMatrix(el.matrix, el.rows, el.cols, el.moduleDots));
      }
      case 'image': {
        if (!el.mono) return '';
        const bmp = MonoBitmap.fromData(el.mono).resample(D(el.width), D(el.height)).rotate(rot.degrees);
        return gw(localRectBounds(el, rot.degrees, 0, 0, el.width, el.height), bmp);
      }
      case 'box': {
        const b = localRectBounds(el, rot.degrees, 0, 0, el.width, el.height);
        const [x, y, w, h] = [D(b.x), D(b.y), Math.max(1, D(b.width)), Math.max(1, D(b.height))];
        if (el.filled) return `LO${x},${y},${w},${h}\n`;
        return `X${x},${y},${Math.max(1, D(el.borderMm))},${x + w},${y + h}\n`;
      }
      case 'error':
        return '';
    }
  },
};
