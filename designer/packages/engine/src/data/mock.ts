import { ean13CheckDigit } from '../barcode/checksum';
import { formatDate } from './format';
import type { DataSet, DataRow } from './sources';

/**
 * Gerador de dados fictícios para desenhar sem ter o CSV real. Determinístico por semente:
 * a mesma semente gera sempre as mesmas linhas (layout reproduzível, testes estáveis).
 */

/** PRNG mulberry32: pequeno, rápido e suficiente para dados de exemplo. */
export function seededRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const PRODUTOS = [
  'Arroz Branco Tipo 1',
  'Feijão Carioca',
  'Café Torrado e Moído',
  'Açúcar Refinado',
  'Leite Integral UHT',
  'Óleo de Soja',
  'Macarrão Espaguete',
  'Teclado Mecânico RGB 87 Teclas',
  'Queijo Muçarela Fatiado',
  'Presunto Cozido sem Capa de Gordura',
  'Detergente Líquido Neutro',
  'Papel Higiênico Folha Dupla',
  'Biscoito Recheado de Chocolate',
  'Filé de Peito de Frango Congelado',
  'Água Mineral sem Gás',
];
const MARCAS = ['Bom Grão', 'Serra Azul', 'Vale Verde', 'Estrela', 'Do Campo', 'Nativa'];
const UNIDADES = ['UN', 'KG', 'PCT', 'CX', 'L'];

export const MOCK_HEADERS = ['SKU', 'EAN', 'PRODUTO', 'MARCA', 'PRECO', 'QTD', 'UNIDADE', 'PESO', 'LOTE', 'FABRICACAO', 'VALIDADE'];

export function generateMockRows(count: number, seed = 42, base = new Date(Date.UTC(2026, 9, 18))): DataSet {
  const rnd = seededRandom(seed);
  const pick = <T,>(arr: readonly T[]) => arr[Math.floor(rnd() * arr.length)]!;
  const digits = (n: number) => Array.from({ length: n }, () => Math.floor(rnd() * 10)).join('');

  const rows: DataRow[] = [];
  for (let i = 0; i < count; i++) {
    const ean12 = '789' + digits(9);
    const fab = new Date(base.getTime() - Math.floor(rnd() * 30) * 86_400_000);
    const val = new Date(fab.getTime() + (30 + Math.floor(rnd() * 330)) * 86_400_000);
    rows.push({
      SKU: `SKU-${String(10000 + Math.floor(rnd() * 90000))}`,
      EAN: ean12 + ean13CheckDigit(ean12),
      PRODUTO: pick(PRODUTOS),
      MARCA: pick(MARCAS),
      PRECO: (1 + rnd() * 499).toFixed(2),
      QTD: String(1 + Math.floor(rnd() * 24)),
      UNIDADE: pick(UNIDADES),
      PESO: (0.1 + rnd() * 9.9).toFixed(3),
      LOTE: `L${digits(2)}${String.fromCharCode(65 + Math.floor(rnd() * 26))}${digits(3)}`,
      FABRICACAO: formatDate(fab, 'YYYY-MM-DD'),
      VALIDADE: formatDate(val, 'YYYY-MM-DD'),
    });
  }
  return { name: `fictícios (${count}, semente ${seed})`, kind: 'mock', headers: [...MOCK_HEADERS], rows };
}
