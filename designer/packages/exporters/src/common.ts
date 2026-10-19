import { boundsOf, mmToDots, type BBox, type Issue, type ResolvedText } from '@athos-label/engine';

/** Rotação arredondada para o quarto de volta mais próximo (o que impressora térmica suporta). */
export function quarterTurn(rotation: number): { q: 0 | 1 | 2 | 3; degrees: number; exact: boolean } {
  const norm = ((rotation % 360) + 360) % 360;
  const q = (Math.round(norm / 90) % 4) as 0 | 1 | 2 | 3;
  return { q, degrees: q * 90, exact: Math.abs(norm - q * 90) < 0.01 || Math.abs(norm - 360) < 0.01 };
}

export function rotationIssue(id: string, rotation: number, degrees: number): Issue {
  return {
    severity: 'warning',
    code: 'UNSUPPORTED',
    message: `Rotação de ${rotation}° não existe na impressora; usada ${degrees}°`,
    objectId: id,
  };
}

/** Ponto local (relativo ao canto do objeto) -> etiqueta, girando em torno do canto. */
export function toLabel(originX: number, originY: number, lx: number, ly: number, degrees: number): { x: number; y: number } {
  const r = (degrees * Math.PI) / 180;
  const cos = Math.cos(r);
  const sin = Math.sin(r);
  return { x: originX + lx * cos - ly * sin, y: originY + lx * sin + ly * cos };
}

/** Caixa visível (AABB) de um retângulo local do objeto depois da rotação. */
export function localRectBounds(
  el: { x: number; y: number },
  degrees: number,
  lx: number,
  ly: number,
  w: number,
  h: number,
): BBox {
  const p = toLabel(el.x, el.y, lx, ly, degrees);
  return boundsOf({ x: p.x, y: p.y, width: w, height: h, rotation: degrees });
}

export const dots = (mm: number, dpi: number) => mmToDots(mm, dpi);

export interface LineLayout {
  text: string;
  /** Topo da caixa da linha, relativo ao objeto. */
  top: number;
  /** Linha de base para desenho com fonte real (Helvetica: ascendente 0,718 em; descendente 0,207). */
  baseline: number;
  /** Ponto de ancoragem horizontal conforme o alinhamento. */
  anchorX: number;
  anchor: 'start' | 'middle' | 'end';
}

/** Posição de cada linha dentro do objeto. Usada pela prévia, SVG, PDF e pelos comandos. */
export function layoutLines(el: ResolvedText): LineLayout[] {
  const block = el.lines.length * el.lineHeightMm;
  const offset = el.valign === 'TOP' ? 0 : el.valign === 'BOTTOM' ? el.height - block : (el.height - block) / 2;
  const anchor = el.align === 'LEFT' ? 'start' : el.align === 'CENTER' ? 'middle' : 'end';
  const anchorX = el.align === 'LEFT' ? 0 : el.align === 'CENTER' ? el.width / 2 : el.width;
  return el.lines.map((text, i) => {
    const top = offset + i * el.lineHeightMm;
    const baseline = top + (el.lineHeightMm - 0.925 * el.fontSizeMm) / 2 + 0.718 * el.fontSizeMm;
    return { text, top, baseline, anchorX, anchor };
  });
}

/** Sequências contínuas de módulos escuros: [início, comprimento]. Menos retângulos no SVG/PDF. */
export function runs(modules: ArrayLike<number>, from = 0, length = modules.length): [number, number][] {
  const out: [number, number][] = [];
  let start = -1;
  for (let i = 0; i < length; i++) {
    const on = modules[from + i] === 1;
    if (on && start < 0) start = i;
    if (!on && start >= 0) {
      out.push([start, i - start]);
      start = -1;
    }
  }
  if (start >= 0) out.push([start, length - start]);
  return out;
}

/**
 * Texto para impressoras de 8 bits (TSPL com CODEPAGE 1252, EPL com I8,A). Caractere fora do
 * Windows-1252 vira "?" e gera alerta (melhor que um byte UTF-8 aparecer como lixo na etiqueta).
 */
const CP1252_EXTRA: Record<string, number> = {
  '€': 0x80, '‚': 0x82, 'ƒ': 0x83, '„': 0x84, '…': 0x85, '†': 0x86, '‡': 0x87, 'ˆ': 0x88, '‰': 0x89,
  'Š': 0x8a, '‹': 0x8b, 'Œ': 0x8c, 'Ž': 0x8e, '‘': 0x91, '’': 0x92, '“': 0x93, '”': 0x94, '•': 0x95,
  '–': 0x96, '—': 0x97, '˜': 0x98, '™': 0x99, 'š': 0x9a, '›': 0x9b, 'œ': 0x9c, 'ž': 0x9e, 'Ÿ': 0x9f,
};

export function toCp1252(text: string, onLoss?: (ch: string) => void): string {
  let out = '';
  for (const ch of text.normalize('NFC')) {
    const code = ch.codePointAt(0)!;
    if (code < 0x80 || (code >= 0xa0 && code <= 0xff)) out += ch;
    else if (CP1252_EXTRA[ch] !== undefined) out += String.fromCharCode(CP1252_EXTRA[ch]!);
    else {
      onLoss?.(ch);
      out += '?';
    }
  }
  return out;
}

/** String em que cada caractere já é um byte (0-255) -> bytes. */
export function latin1Bytes(s: string): Uint8Array {
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xff;
  return out;
}

export function bytesToLatin1(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 8192) s += String.fromCharCode(...b.subarray(i, i + 8192));
  return s;
}
