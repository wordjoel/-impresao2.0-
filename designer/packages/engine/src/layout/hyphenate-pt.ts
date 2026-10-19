/**
 * Pontos de hifenização do português (Brasil) — regras de separação silábica, versão conservadora.
 *
 * Só devolve pontos SEGUROS para uma etiqueta impressa:
 *  - nunca separa duas vogais seguidas (ditongo × hiato exige dicionário; errar "sai-a" seria pior
 *    que não hifenizar);
 *  - não separa os dígrafos ch, lh, nh, qu, gu nem os encontros consonantais com l/r (bl, pr, tr…);
 *  - separa rr, ss, sc, sç, xc, xs e os demais pares de consoantes (ex.: "ap-to", "ad-mi-tir");
 *  - pelo menos 2 letras antes e depois do hífen;
 *  - palavra com número ou símbolo no meio (SKU, código, URL) não é hifenizada.
 *
 * Pontuação no fim ("Mecânico," "promoção!") é aceita e nunca é separada da palavra.
 */

const VOWELS = new Set('aeiouáéíóúâêôãõàüy');
/** Encontros que abrem sílaba juntos (consoante + l/r). */
const ONSET_CLUSTERS = new Set(['bl', 'br', 'cl', 'cr', 'dl', 'dr', 'fl', 'fr', 'gl', 'gr', 'kl', 'kr', 'pl', 'pr', 'tl', 'tr', 'vl', 'vr']);
/** Dígrafos inseparáveis. "qu"/"gu" são tratados à parte (o "u" é semivogal ou mudo). */
const DIGRAPHS = new Set(['ch', 'lh', 'nh']);

const MIN_EDGE = 2;

const isVowel = (c: string) => VOWELS.has(c);

/** O "u" de qu/gu conta como parte da consoante, não como vogal. */
function vowelMask(w: string): boolean[] {
  const mask = [...w].map(isVowel);
  for (let i = 1; i < w.length; i++) {
    if (w[i] === 'u' && (w[i - 1] === 'q' || w[i - 1] === 'g') && isVowel(w.charAt(i + 1))) mask[i] = false;
  }
  return mask;
}

function keepsTogether(a: string, b: string): boolean {
  const pair = a + b;
  return ONSET_CLUSTERS.has(pair) || DIGRAPHS.has(pair) || ((a === 'q' || a === 'g') && b === 'u');
}

/**
 * Índices onde a palavra pode ser quebrada (o hífen entra ANTES do índice). Vazio quando não há
 * ponto seguro.
 */
export function hyphenationPoints(word: string): number[] {
  const m = /^(\p{L}+)(\p{P}*)$/u.exec(word);
  const letters = m?.[1];
  if (!letters) return [];
  const w = letters.toLowerCase();
  if (w.length < MIN_EDGE * 2) return [];
  const v = vowelMask(w);

  const points: number[] = [];
  let i = 0;
  while (i < w.length && !v[i]) i++; // consoantes iniciais ficam com a 1ª sílaba
  while (i < w.length) {
    // fim do núcleo vocálico atual (vogais seguidas não são separadas)
    while (i < w.length && v[i]) i++;
    let j = i;
    while (j < w.length && !v[j]) j++; // [i, j) = consoantes até a próxima vogal
    if (j >= w.length) break; // consoantes finais ficam com a última sílaba
    const n = j - i;
    let cut: number;
    if (n === 1) cut = i; // V-CV
    else if (keepsTogether(w.charAt(j - 2), w.charAt(j - 1))) cut = j - 2; // ...-CCV (pr, bl, ch, lh, nh, qu…)
    else cut = j - 1; // VC-CV, inclusive rr, ss, sc, sç, xc
    if (cut >= MIN_EDGE && letters.length - cut >= MIN_EDGE) points.push(cut);
    i = j;
  }
  return points;
}

/**
 * Maior prefixo hifenizável de `word` que satisfaz `fits(prefixo + "-")`. Devolve o índice do
 * corte, ou 0 se nenhum ponto serve.
 */
export function bestHyphenation(word: string, fits: (headWithHyphen: string) => boolean): number {
  for (const p of hyphenationPoints(word).reverse()) {
    if (fits(`${word.slice(0, p)}-`)) return p;
  }
  return 0;
}
