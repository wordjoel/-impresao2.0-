/**
 * Etiqueta de teste do Passo 1 (docs/PLANO-DESIGNER-ETIQUETAS-2026-10-19.md): validação física.
 *
 * Gera, pelo MESMO motor do designer (resolveLabel + exportador), os bytes para a impressora, uma
 * prévia SVG e a tabela com a posição esperada de cada objeto (para conferir com régua).
 *
 *   npx tsx tools/etiqueta-teste-fisico.ts --largura 100 --altura 150 --dpi 203 --lingua TSPL --saida <pasta>
 *
 * Não envia nada para impressora: o envio é um passo separado e explícito.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  addObject,
  createBarcode1D,
  createBarcode2D,
  createBox,
  createImageObject,
  createTemplate,
  createTextObject,
  MonoBitmap,
  resolveLabel,
  type LabelTemplate,
  type PrinterLanguage,
} from '@athos-label/engine';
import { GENERATORS, labelToCommands, labelToSvg } from '@athos-label/exporters';

function arg(name: string, fallback: string): string {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const largura = Number(arg('largura', '100'));
const altura = Number(arg('altura', '150'));
const dpi = Number(arg('dpi', '203'));
const lingua = arg('lingua', 'TSPL') as PrinterLanguage;
const saida = arg('saida', 'etiqueta-teste');

// O layout ocupa 96 x 96 mm; rolo menor que isso precisa de outro layout (não encolher em silêncio).
if (largura < 100 || altura < 100) {
  console.error(`Rolo ${largura}x${altura} mm é menor que o layout de teste (100x100 mm). Ajuste o layout antes.`);
  process.exit(2);
}

/** Alvo de posição: moldura, cruz e diagonal, para medir deslocamento e espelhamento. */
function alvo(px: number): MonoBitmap {
  const b = new MonoBitmap(px, px);
  for (let i = 0; i < px; i++) {
    for (let t = 0; t < 3; t++) {
      b.set(i, t, true); b.set(i, px - 1 - t, true); b.set(t, i, true); b.set(px - 1 - t, i, true);
    }
    b.set(i, i, true);
    b.set(i, px >> 1, true); b.set(px >> 1, i, true);
  }
  // marca no canto superior esquerdo: revela imagem girada ou espelhada
  for (let y = 6; y < 20; y++) for (let x = 6; x < 20; x++) b.set(x, y, true);
  return b;
}

let t: LabelTemplate = createTemplate(largura, altura, 'Teste físico — Passo 1');
const objetos = [
  createBox({ id: 'moldura', name: 'Moldura', x: 2, y: 2, width: 96, height: 96, borderMm: 0.3 }),
  createTextObject({ id: 'acentos', name: 'Texto acentuado', content: 'AÇÚCAR ÁGUA PÃO ÊXITO', x: 5, y: 5, width: 70, height: 6,
    font: { family: 'Helvetica', sizeMm: 4, bold: false, italic: false }, fit: 'NONE' }),
  createTextObject({ id: 'girado', name: 'Texto girado 90°', content: 'GIRADO 90', x: 70, y: 40, width: 40, height: 6, rotation: 90,
    font: { family: 'Helvetica', sizeMm: 4, bold: false, italic: false }, fit: 'NONE' }),
  createBarcode1D({ id: 'ean13', name: 'EAN-13', symbology: 'EAN13', content: '4006381333931', x: 5, y: 15, width: 45, height: 20 }),
  createBarcode1D({ id: 'code128', name: 'Code128', symbology: 'CODE128', content: 'ATHOS-123456', x: 5, y: 40, width: 55, height: 15 }),
  createBarcode2D({ id: 'qr', name: 'QR', symbology: 'QR', content: 'https://athos.com.br/teste-fisico', x: 60, y: 8, width: 25, height: 25 }),
  createBarcode2D({ id: 'datamatrix', name: 'DataMatrix', symbology: 'DATAMATRIX', content: 'ATHOS-DM-0001', x: 60, y: 62, width: 18, height: 18 }),
  createImageObject({ id: 'imagem', name: 'Imagem (alvo)', src: 'data:image/png;base64,', mono: alvo(Math.round((20 * dpi) / 25.4)).toData(),
    x: 5, y: 62, width: 20, height: 20 }),
  createTextObject({ id: 'legenda', name: 'Legenda', content: `TESTE FISICO ${largura}x${altura}mm ${dpi}dpi ${lingua}`, x: 5, y: 88, width: 90, height: 5,
    font: { family: 'Helvetica', sizeMm: 3, bold: false, italic: false }, fit: 'NONE' }),
];
// Passo 2 (2026-10-19): simbologias novas, na faixa de baixo — só cabem em rolo de 150 mm.
if (altura >= 150) {
  objetos.push(
    createBox({ id: 'moldura2', name: 'Moldura (passo 2)', x: 2, y: 100, width: 96, height: 47, borderMm: 0.3 }),
    createBarcode1D({ id: 'ean8', name: 'EAN-8', symbology: 'EAN8', content: '9638507', x: 5, y: 103, width: 30, height: 15 }),
    createBarcode1D({ id: 'itf14', name: 'ITF-14', symbology: 'ITF14', content: '1001234567890', x: 40, y: 103, width: 55, height: 18 }),
    createBarcode1D({ id: 'gs1128', name: 'GS1-128', symbology: 'GS1_128', content: '(01)10012345678902(10)LOTE1', x: 5, y: 125, width: 90, height: 18 }),
  );
} else {
  console.warn('Rolo com menos de 150 mm: EAN-8, ITF-14 e GS1-128 ficam de fora desta etiqueta.');
}
for (const o of objetos) t = addObject(t, o);

const resolved = resolveLabel(t, {}, { dpi });
const gen = GENERATORS[lingua];
const { commands, warnings } = labelToCommands(gen, resolved) as { commands: string; warnings?: string[] };
const bytes = gen.encode(commands);

mkdirSync(saida, { recursive: true });
const ext = lingua.toLowerCase();
writeFileSync(join(saida, `etiqueta-teste.${ext}`), bytes);
writeFileSync(join(saida, 'etiqueta-teste.svg'), labelToSvg(resolved));

const linhas = objetos.map((o) => `| ${o.name} | ${o.x} | ${o.y} | ${o.width} | ${o.height} | ${o.rotation} | ${'content' in o ? o.content : ''} | | |`);
writeFileSync(
  join(saida, 'posicoes-esperadas.md'),
  [
    `# Posições esperadas — ${largura}x${altura} mm, ${dpi} dpi, ${lingua}`,
    '',
    'Medir da borda ESQUERDA e do TOPO da etiqueta até o canto superior esquerdo de cada objeto.',
    'Aceite: diferença de até ±0,5 mm. Códigos: valor lido pelo scanner do CF550 igual ao conteúdo.',
    '',
    '| Objeto | x (mm) | y (mm) | largura | altura | rotação | conteúdo | medido x / y | leitura scanner |',
    '|---|---|---|---|---|---|---|---|---|',
    ...linhas,
    '',
    `Avisos do exportador: ${warnings && warnings.length ? warnings.join('; ') : 'nenhum'}`,
    '',
  ].join('\n'),
);
console.log(`ok: ${bytes.length} bytes ${lingua} em ${saida}; avisos: ${warnings?.length ?? 0}`);
