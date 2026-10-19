// ATENÇÃO: usar o export DEFAULT. O export nomeado `raw` do build ESM é a simbologia BWIPP 'raw',
// não a função que devolve barras/matriz (essa é o `raw` do objeto default, ToRaw).
import bwipjs from 'bwip-js';
import type { QrErrorLevel, Symbology1D, Symbology2D } from '../model/objects';
import { ean13CheckDigit, gs1Check, isValidEan13, isValidGs1Number, isValidUpcA, upcaCheckDigit } from './checksum';

/**
 * Simbologias 1D desenhadas como IMAGEM nas linguagens de impressora (ZPL/TSPL/EPL), a partir da
 * mesma codificação da prévia. Motivo (2026-10-19): os comandos nativos de ITF-14 e GS1-128 mudam
 * entre ZPL, TSPL e EPL (FNC1, razão de barras, verificador) e não foram validados em impressora;
 * como imagem, com módulo inteiro em dots, a etiqueta sai idêntica à prévia em qualquer uma.
 * Só sai daqui com validação física do comando nativo.
 */
export const RASTER_1D: ReadonlySet<Symbology1D> = new Set<Symbology1D>(['ITF14', 'GS1_128']);

/** AIs com número GS1 de tamanho fixo e verificador. */
const GS1_FIXED_CHECKED: Record<string, number> = { '00': 18, '01': 14, '02': 14 };

/**
 * GS1-128 no formato legível "(01)07891234567895(17)261231(10)LOTE1". Valida a forma, o tamanho
 * máximo (48 caracteres de dado) e o verificador dos AIs 00/01/02; o resto dos AIs é conferido
 * pelo codificador (lint GS1 do bwip-js).
 */
function normalizeGs1(value: string): string {
  const v = value.replace(/\s+/g, '');
  if (!/^(\(\d{2,4}\)[^()]+)+$/.test(v)) {
    throw new BarcodeError(`GS1-128 precisa do formato (AI)dado, ex.: (01)07891234567895(10)LOTE1 ("${value}")`);
  }
  let length = 0;
  for (const [, ai, data] of v.matchAll(/\((\d{2,4})\)([^()]+)/g)) {
    length += ai!.length + data!.length;
    const fixed = GS1_FIXED_CHECKED[ai!];
    if (fixed === undefined) continue;
    if (!new RegExp(`^\\d{${fixed}}$`).test(data!)) throw new BarcodeError(`GS1-128: AI (${ai}) precisa de ${fixed} dígitos ("${data}")`);
    if (!isValidGs1Number(data!)) {
      throw new BarcodeError(`GS1-128: AI (${ai}) com dígito verificador errado ("${data}", esperado ${gs1Check(data!.slice(0, -1))})`);
    }
  }
  if (length > 48) throw new BarcodeError(`GS1-128 aceita no máximo 48 caracteres de dado (tem ${length})`);
  return v;
}

/**
 * Codificação de códigos de barras. A MESMA codificação alimenta a prévia, SVG, PDF e o cálculo
 * de módulo para ZPL/TSPL/EPL — o que aparece na tela tem a mesma contagem de módulos do que a
 * impressora vai gerar.
 */

export class BarcodeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BarcodeError';
  }
}

export interface Encoded1D {
  symbology: Symbology1D;
  /** Dado normalizado como vai para a impressora (EAN/UPC sem o verificador; ela calcula). */
  printerData: string;
  /** Dado completo (com verificador) exibido abaixo das barras. */
  humanText: string;
  /** Um item por módulo: 1 = barra, 0 = espaço. Sem zona de silêncio. */
  modules: Uint8Array;
}

export interface Encoded2D {
  symbology: Symbology2D;
  data: string;
  rows: number;
  cols: number;
  /** rows × cols, por linha, 1 = módulo escuro. Sem zona de silêncio. */
  matrix: Uint8Array;
}

const CODE39_CHARS = /^[0-9A-Z \-.$/+%]*$/;

/** Valida e normaliza. Erro com mensagem para o operador, não para o programador. */
export function normalize1D(symbology: Symbology1D, raw: string): { printerData: string; full: string } {
  const value = raw.trim();
  if (value === '') throw new BarcodeError('Código de barras vazio');
  switch (symbology) {
    case 'EAN13': {
      const d = value.replace(/\s/g, '');
      if (/^\d{12}$/.test(d)) return { printerData: d, full: d + ean13CheckDigit(d) };
      if (!/^\d{13}$/.test(d)) throw new BarcodeError(`EAN-13 precisa de 12 ou 13 dígitos ("${value}")`);
      if (!isValidEan13(d)) throw new BarcodeError(`EAN-13 com dígito verificador errado ("${value}", esperado ${ean13CheckDigit(d.slice(0, 12))})`);
      return { printerData: d.slice(0, 12), full: d };
    }
    case 'UPCA': {
      const d = value.replace(/\s/g, '');
      if (/^\d{11}$/.test(d)) return { printerData: d, full: d + upcaCheckDigit(d) };
      if (!/^\d{12}$/.test(d)) throw new BarcodeError(`UPC-A precisa de 11 ou 12 dígitos ("${value}")`);
      if (!isValidUpcA(d)) throw new BarcodeError(`UPC-A com dígito verificador errado ("${value}")`);
      return { printerData: d.slice(0, 11), full: d };
    }
    case 'EAN8': {
      const d = value.replace(/\s/g, '');
      if (/^\d{7}$/.test(d)) return { printerData: d, full: d + gs1Check(d) };
      if (!/^\d{8}$/.test(d)) throw new BarcodeError(`EAN-8 precisa de 7 ou 8 dígitos ("${value}")`);
      if (!isValidGs1Number(d)) throw new BarcodeError(`EAN-8 com dígito verificador errado ("${value}", esperado ${gs1Check(d.slice(0, 7))})`);
      return { printerData: d.slice(0, 7), full: d };
    }
    case 'ITF14': {
      const d = value.replace(/\s/g, '');
      const full = /^\d{13}$/.test(d) ? d + gs1Check(d) : d;
      if (!/^\d{14}$/.test(full)) throw new BarcodeError(`ITF-14 precisa de 13 ou 14 dígitos ("${value}")`);
      if (!isValidGs1Number(full)) throw new BarcodeError(`ITF-14 com dígito verificador errado ("${value}", esperado ${gs1Check(full.slice(0, 13))})`);
      // Desenhado como imagem (RASTER_1D): a impressora recebe as barras, não este dado.
      return { printerData: full, full };
    }
    case 'GS1_128': {
      const full = normalizeGs1(value);
      return { printerData: full, full };
    }
    case 'CODE39': {
      const d = value.toUpperCase();
      if (!CODE39_CHARS.test(d)) throw new BarcodeError(`Code 39 aceita só A-Z, 0-9 e - . $ / + % espaço ("${value}")`);
      return { printerData: d, full: d };
    }
    case 'CODE128': {
      // eslint-disable-next-line no-control-regex
      if (!/^[\x20-\x7E]*$/.test(value)) {
        throw new BarcodeError(`Code 128 aceita só ASCII imprimível; remova acentos ("${value}")`);
      }
      return { printerData: value, full: value };
    }
  }
}

const BCID_1D: Record<Symbology1D, string> = {
  CODE128: 'code128',
  EAN13: 'ean13',
  UPCA: 'upca',
  CODE39: 'code39',
  EAN8: 'ean8',
  ITF14: 'itf14',
  GS1_128: 'gs1-128',
};

type RawResult = { sbs?: number[]; pixs?: number[]; pixx?: number; pixy?: number };

function raw(bcid: string, text: string, opts: Record<string, unknown>): RawResult {
  try {
    // Os tipos do bwip-js não aceitam um objeto genérico de opções; o formato real é o BwippOptions.
    const out = (bwipjs.raw as (b: string, t: string, o: object) => RawResult[])(bcid, text, opts);
    return out[0]!;
  } catch (e) {
    throw new BarcodeError(`Não foi possível codificar: ${String((e as Error).message ?? e).replace(/^bwipp\.\w+#?\d*:\s*/, '')}`);
  }
}

const cache1D = new Map<string, Encoded1D>();

export function encode1D(symbology: Symbology1D, rawValue: string): Encoded1D {
  const key = `${symbology}|${rawValue}`;
  const hit = cache1D.get(key);
  if (hit) return hit;

  const { printerData, full } = normalize1D(symbology, rawValue);
  const r = raw(BCID_1D[symbology], full, {});
  if (!r.sbs) throw new BarcodeError('Codificador não devolveu barras');
  const total = r.sbs.reduce((a, b) => a + b, 0);
  const modules = new Uint8Array(total);
  let pos = 0;
  r.sbs.forEach((w, i) => {
    if (i % 2 === 0) modules.fill(1, pos, pos + w); // sbs alterna barra/espaço começando por barra
    pos += w;
  });
  const enc: Encoded1D = { symbology, printerData, humanText: full, modules };
  if (cache1D.size > 2000) cache1D.clear();
  cache1D.set(key, enc);
  return enc;
}

/**
 * Quantos módulos o EAN/UPC/Code 128/Code 39 terá, sem desenhar. EAN-13 e UPC-A têm sempre 95;
 * os demais dependem do conteúdo e usam a codificação (com cache).
 */
export function moduleCount1D(symbology: Symbology1D, rawValue: string): { printerData: string; humanText: string; count: number } {
  if (symbology === 'EAN13' || symbology === 'UPCA' || symbology === 'EAN8') {
    const { printerData, full } = normalize1D(symbology, rawValue);
    return { printerData, humanText: full, count: symbology === 'EAN8' ? 67 : 95 };
  }
  const enc = encode1D(symbology, rawValue);
  return { printerData: enc.printerData, humanText: enc.humanText, count: enc.modules.length };
}

const sizeCache = new Map<string, { rows: number; cols: number }>();

/**
 * Tamanho do símbolo 2D (em módulos) sem codificar o dado. Calculado UMA vez por comprimento:
 * codifica um texto-representante do pior caso, então o tamanho real nunca é maior que este.
 *  - QR: modo byte ("a" minúsculo não é alfanumérico), o modo de menor capacidade.
 *  - DataMatrix: "!" não comprime em C40/Text, então é 1 palavra-código por caractere (ASCII);
 *    bytes acima de 127 custam 2. O tamanho calculado é IMPOSTO ao bwip e à impressora, e a
 *    prévia fica idêntica ao que sai na etiqueta.
 * Sem isso, codificar QR custava ~1,5 ms por etiqueta (15 s em 10.000).
 */
export function symbolSize2D(symbology: Symbology2D, data: string, ecLevel: QrErrorLevel = 'M'): { rows: number; cols: number } {
  if (data === '') throw new BarcodeError('Código 2D vazio');
  const bytes = new TextEncoder().encode(data);
  const units = symbology === 'QR' ? bytes.length : bytes.reduce((n, b) => n + (b > 127 ? 2 : 1), 0);
  const key = `${symbology}|${ecLevel}|${units}`;
  const hit = sizeCache.get(key);
  if (hit) return hit;
  const r = symbology === 'QR' ? raw('qrcode', 'a'.repeat(units), { eclevel: ecLevel }) : raw('datamatrix', '!'.repeat(units), {});
  if (!r.pixx || !r.pixy) throw new BarcodeError('Codificador não devolveu matriz');
  const size = { rows: r.pixy, cols: r.pixx };
  sizeCache.set(key, size);
  return size;
}

const cache2D = new Map<string, Encoded2D>();

export function encode2D(symbology: Symbology2D, data: string, ecLevel: QrErrorLevel = 'M'): Encoded2D {
  if (data === '') throw new BarcodeError('Código 2D vazio');
  const key = `${symbology}|${ecLevel}|${data}`;
  const hit = cache2D.get(key);
  if (hit) return hit;

  let r: RawResult;
  if (symbology === 'QR') r = raw('qrcode', data, { eclevel: ecLevel });
  else {
    const size = symbolSize2D('DATAMATRIX', data);
    r = raw('datamatrix', data, { rows: size.rows, columns: size.cols });
  }
  if (!r.pixs || !r.pixx || !r.pixy) throw new BarcodeError('Codificador não devolveu matriz');
  const enc: Encoded2D = {
    symbology,
    data,
    rows: r.pixy,
    cols: r.pixx,
    matrix: Uint8Array.from(r.pixs),
  };
  if (cache2D.size > 2000) cache2D.clear();
  cache2D.set(key, enc);
  return enc;
}

/**
 * Largura de módulo em dots: o maior inteiro que faz o código caber na largura do objeto.
 * Barras com meia-largura de dot não existem na cabeça de impressão; por isso o módulo é sempre
 * inteiro, e a largura real impressa pode ficar menor que a caixa (é o que a prévia mostra).
 */
export function moduleDots(availableDots: number, moduleCount: number, max = 10): number {
  return Math.max(0, Math.min(max, Math.floor(availableDots / moduleCount)));
}
