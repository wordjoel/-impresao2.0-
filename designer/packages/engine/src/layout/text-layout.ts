import type { TextFit } from '../model/objects';
import { bestHyphenation } from './hyphenate-pt';
import type { MeasureFont, TextMeasurer } from './metrics';

/** Altura de linha como fração do tamanho da fonte. */
export const LINE_HEIGHT = 1.15;

/**
 * Quebra de linha por largura medida. Respeita "\n" explícito; quebra em espaços; palavra maior
 * que a largura é quebrada por caractere (um SKU longo não pode sumir da etiqueta).
 *
 * Com `hyphenate` (2026-10-19, opcional por objeto): antes de mandar a palavra inteira para a
 * próxima linha, tenta encaixar um pedaço dela com hífen numa sílaba do português
 * (`hyphenate-pt.ts`). Sem ponto seguro, cai no comportamento acima.
 */
export function wrapText(text: string, maxWidth: number, font: MeasureFont, m: TextMeasurer, hyphenate = false): string[] {
  const lines: string[] = [];
  for (const paragraph of text.split('\n')) {
    const words = paragraph.split(/ +/).filter((w, _i, arr) => w !== '' || arr.length === 1);
    let current = '';
    for (const word of words) {
      let rest = word;
      for (;;) {
        const candidate = current === '' ? rest : `${current} ${rest}`;
        if (m.width(candidate, font) <= maxWidth) {
          current = candidate;
          break;
        }
        if (hyphenate) {
          const head = current === '' ? '' : `${current} `;
          const cut = bestHyphenation(rest, (piece) => m.width(head + piece, font) <= maxWidth);
          if (cut > 0) {
            lines.push(`${head}${rest.slice(0, cut)}-`);
            current = '';
            rest = rest.slice(cut);
            continue;
          }
        }
        if (current !== '') {
          lines.push(current);
          current = '';
          continue;
        }
        // Palavra sozinha não cabe: corta por caractere.
        for (const ch of rest) {
          if (current !== '' && m.width(current + ch, font) > maxWidth) {
            lines.push(current);
            current = ch;
          } else current += ch;
        }
        break;
      }
    }
    lines.push(current);
  }
  return lines;
}

/** Corta a linha e põe reticências até caber. */
export function ellipsize(line: string, maxWidth: number, font: MeasureFont, m: TextMeasurer): string {
  if (m.width(line, font) <= maxWidth) return line;
  let s = line;
  while (s.length > 0 && m.width(`${s}…`, font) > maxWidth) s = s.slice(0, -1);
  return s.length > 0 ? `${s.trimEnd()}…` : '';
}

export interface FitResult {
  sizeMm: number;
  lines: string[];
  lineHeightMm: number;
  /** Nem no tamanho mínimo coube: o texto foi cortado (vira alerta na validação). */
  overflow: boolean;
}

export interface FitInput {
  text: string;
  widthMm: number;
  heightMm: number;
  font: MeasureFont;
  fit: TextFit;
  minSizeMm: number;
  /** Hifenização em português nas quebras (só com WRAP/WRAP_SHRINK). */
  hyphenate?: boolean;
}

function layoutAt(input: FitInput, sizeMm: number, wrap: boolean, m: TextMeasurer) {
  const font = { ...input.font, sizeMm };
  const lines = wrap ? wrapText(input.text, input.widthMm, font, m, input.hyphenate === true) : input.text.split('\n');
  const lineHeightMm = sizeMm * LINE_HEIGHT;
  const fitsH = lines.length * lineHeightMm <= input.heightMm + 1e-9 || (lines.length === 1 && sizeMm <= input.heightMm + 1e-9);
  const fitsW = lines.every((l) => m.width(l, font) <= input.widthMm + 1e-9);
  return { font, lines, lineHeightMm, fits: fitsH && fitsW };
}

/**
 * Auto-fit: o Bartender quebra a linha; aqui o texto se ADAPTA. Em WRAP_SHRINK, busca binária
 * pelo MAIOR tamanho (entre o mínimo e o tamanho do objeto) em que o texto quebrado cabe na
 * caixa. Se nem o mínimo couber, corta as linhas que sobram e põe reticências (overflow = true).
 */
export function fitText(input: FitInput, m: TextMeasurer): FitResult {
  const wrap = input.fit === 'WRAP' || input.fit === 'WRAP_SHRINK';
  const shrink = input.fit === 'SHRINK' || input.fit === 'WRAP_SHRINK';
  const max = input.font.sizeMm;
  const min = Math.min(input.minSizeMm, max);

  let chosen = layoutAt(input, max, wrap, m);
  if (!chosen.fits && shrink) {
    let lo = min;
    let hi = max;
    let best = layoutAt(input, min, wrap, m);
    if (best.fits) {
      // Invariante: `lo` cabe, `hi` não. Precisão de 0,05 mm (abaixo de 1 dot a 203 dpi).
      while (hi - lo > 0.05) {
        const mid = (lo + hi) / 2;
        const attempt = layoutAt(input, mid, wrap, m);
        if (attempt.fits) {
          lo = mid;
          best = attempt;
        } else hi = mid;
      }
    }
    chosen = best;
  }

  if (chosen.fits) {
    return { sizeMm: round2(chosen.font.sizeMm), lines: chosen.lines, lineHeightMm: chosen.lineHeightMm, overflow: false };
  }

  // Não coube: mantém o que cabe na altura e marca a última linha com reticências.
  const maxLines = Math.max(1, Math.floor((input.heightMm + 1e-9) / chosen.lineHeightMm));
  const kept = chosen.lines.slice(0, maxLines);
  if (chosen.lines.length > maxLines) kept[kept.length - 1] = `${kept[kept.length - 1]}…`;
  const lines = kept.map((l) => ellipsize(l, input.widthMm, chosen.font, m));
  return { sizeMm: round2(chosen.font.sizeMm), lines, lineHeightMm: chosen.lineHeightMm, overflow: true };
}

const round2 = (v: number) => Math.floor(v * 100) / 100;
