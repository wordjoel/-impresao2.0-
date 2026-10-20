/**
 * Gera a fixture do teste cruzado TypeScript × Kotlin (passo 6c):
 *   android/app/src/test/resources/coletor/fixture.json
 *
 * Publica um template de verdade pelo ColetorService (mesmo caminho do print-server), com uma chave
 * de assinatura NOVA num diretório temporário que é apagado no fim: só a chave PÚBLICA vai para a
 * fixture. Junto vão casos de montagem (valores → SHA-256 dos bytes) e de lacuna (valor → saída ou
 * recusa), calculados pela referência TS. O teste Kotlin tem de chegar exatamente nos mesmos.
 *
 * Rodar de designer/apps/print-server:  npx tsx scripts/gen-coletor-fixture.ts
 */
import { createHash, createPublicKey } from 'node:crypto';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  addObject,
  canonicalJson,
  createBarcode1D,
  createBarcode2D,
  createBox,
  createImageObject,
  createTemplate,
  createTextObject,
  sha256Hex,
} from '@athos-label/engine';
import { assembleLabel, fillSlot, SlotValueError, type ColetorLanguage, type LabelPackage, type PackageSlot } from '@athos-label/exporters';
import { ColetorService } from '../src/coletor';
import { FileStore } from '../src/store';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.resolve(here, '../../../../android/app/src/test/resources/coletor/fixture.json');

let t = createTemplate(80, 50, 'Fixture coletor — Gôndola');
t = { ...t, id: 'fixture-gondola' };
t = addObject(t, createTextObject({ id: 'loja', name: 'Loja', content: 'Açougue & Cia "Centro"', x: 2, y: 1, width: 50, height: 5, fit: 'NONE' }));
t = addObject(t, createTextObject({ id: 'lote', name: 'Lote', content: 'Lote {{LOTE}} / {{TURNO}}', x: 2, y: 6, width: 60, height: 5, fit: 'NONE' }));
t = addObject(t, createTextObject({ id: 'desc', name: 'Descrição', content: '{{DESC}}', x: 2, y: 11, width: 45, height: 6, fit: 'NONE', alignment: 'CENTER', font: { family: 'Helvetica', sizeMm: 3.5, bold: true, italic: false } }));
t = addObject(t, createTextObject({ id: 'girado', name: 'Girado', content: '{{TURNO}}', x: 75, y: 5, width: 20, height: 4, fit: 'NONE', rotation: 90 }));
t = addObject(t, createBarcode1D({ id: 'ean', name: 'EAN', symbology: 'EAN13', content: '{{EAN}}', x: 4, y: 18, width: 40, height: 12 }));
t = addObject(t, createBarcode1D({ id: 'ean8', name: 'EAN-8', symbology: 'EAN8', content: '{{EAN8}}', x: 50, y: 18, width: 25, height: 10 }));
t = addObject(t, createBarcode1D({ id: 'upc', name: 'UPC', symbology: 'UPCA', content: '{{UPC}}', x: 50, y: 30, width: 28, height: 10, humanReadable: false }));
t = addObject(t, createBarcode1D({ id: 'sku', name: 'SKU', content: '{{SKU}}', x: 2, y: 32, width: 45, height: 8 }));
t = addObject(t, createBarcode2D({ id: 'qr', name: 'QR', content: 'https://ex.test/p/{{SKU}}?l={{LOTE}}', x: 62, y: 1, width: 12, height: 12, ecLevel: 'Q' }));
t = addObject(t, createBox({ id: 'moldura', x: 0.5, y: 0.5, width: 79, height: 49, borderMm: 0.3 }));
// Imagem estática: no TSPL vira BITMAP binário dentro de um `lit` (bytes 0x00–0xFF no JSON).
const bits = Buffer.alloc(32);
for (let i = 0; i < 32; i++) bits[i] = (i * 37 + 11) & 0xff;
t = addObject(t, createImageObject({ id: 'logo', name: 'Logo', src: 'data:image/png;base64,', x: 2, y: 42, width: 6, height: 6, mono: { width: 16, height: 16, data: bits.toString('base64') } }));

const samples = { LOTE: 'L-001', TURNO: 'Manhã', DESC: 'Picanha', EAN: '7891234567895', EAN8: '12345670', UPC: '036000291452', SKU: 'PIC-0001' };
const targets = [
  { language: 'ZPL', dpi: 203 },
  { language: 'ZPL', dpi: 300 },
  { language: 'TSPL', dpi: 203 },
  { language: 'TSPL', dpi: 300 },
] as const;

const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'athos-fixture-'));
try {
  const coletor = new ColetorService(new FileStore(dir), { serverUrl: 'http://fixture.invalid:7172', now: () => new Date('2026-10-20T12:00:00Z') });
  await coletor.publish({ template: t, templateVersion: 7, templateHash: await sha256Hex(canonicalJson(t)), targets: [...targets], user: 'fixture', samples });
  const prov = await coletor.registerDevice('fixture'); // o token não vai para a fixture
  const envelope = await coletor.envelope(t.id);
  const pkg = JSON.parse(envelope.packageJson) as LabelPackage;

  const valueSets: Record<string, string>[] = [
    samples,
    { LOTE: 'ç^~_\\"x', TURNO: 'Noite', DESC: 'Açaí "€" 1', EAN: '789123456789', EAN8: '7654321', UPC: '03600029145', SKU: ' A"^~_\\ ' },
    { LOTE: '😀', TURNO: 'é', DESC: 'ÀÉÎÕÜ ñ ß ÿ', EAN: ' 7891234567895 ', EAN8: '1234 5670', UPC: '03600029145 2', SKU: 'Z' },
  ];
  const cases: { target: number; values: Record<string, string>; copies: number; result: string }[] = [];
  pkg.targets.forEach((tg, i) => {
    valueSets.forEach((values, j) => {
      let result: string;
      try {
        result = createHash('sha256').update(assembleLabel(tg, values, j + 1)).digest('hex');
      } catch (e) {
        if (!(e instanceof SlotValueError)) throw e;
        result = `${e.code}:${e.field}`;
      }
      cases.push({ target: i, values, copies: j + 1, result });
    });
  });

  const slots: PackageSlot[] = [
    { id: 'a', field: 'T', kind: 'TEXT', transform: 'NONE', maxLength: 10 },
    { id: 'b', field: 'E13', kind: 'EAN13', transform: 'GS1_DATA', maxLength: 12 },
    { id: 'c', field: 'E8', kind: 'EAN8', transform: 'GS1_DATA', maxLength: 7 },
    { id: 'd', field: 'U', kind: 'UPCA', transform: 'GS1_DATA', maxLength: 11 },
    { id: 'e', field: 'C', kind: 'CODE128', transform: 'NONE', maxLength: 12 },
    { id: 'f', field: 'Q', kind: 'QR', transform: 'NONE', maxLength: 12 },
  ];
  const values = [
    '', 'abc', 'ação', 'á', '́a', 'x'.repeat(10), 'x'.repeat(11), 'a^b~c_d\\e', 'diz "oi"', '€‚ƒ„…†‡ˆ‰Š‹ŒŽ',
    'seta →', 'tab\tx', 'del\u007f', 'c1\u0085', '😀😀', 'a\ud800b', ' 789123456789 ', '7891234567895', '7891234567890',
    '789123456789', ' 7891234567895 ', '1234567', '12345670', '12345678', '03600029145', '036000291452', '036000291453',
    'ÿ', 'ç'.repeat(4), 'ÇÃ'.repeat(3), ' ', 'Ω', 'ß', '١٢٣',
  ];
  const slotCases: { slot: string; language: ColetorLanguage; value: string; result: string }[] = [];
  for (const slot of slots) {
    for (const language of ['ZPL', 'TSPL'] as const) {
      for (const value of values) {
        let result: string;
        try {
          result = `OK:${fillSlot(slot, value, language)}`;
        } catch (e) {
          if (!(e instanceof SlotValueError)) throw e;
          result = e.code;
        }
        slotCases.push({ slot: slot.id, language, value, result });
      }
    }
  }

  const spki = createPublicKey(prov.signingKey.publicKeyPem).export({ type: 'spki', format: 'der' }).toString('base64');
  const fixture = {
    note: 'Gerado por designer/apps/print-server/scripts/gen-coletor-fixture.ts. Chave de TESTE descartada; só a pública está aqui.',
    kid: prov.signingKey.kid,
    publicKeySpkiB64: spki,
    envelope,
    slots,
    cases,
    slotCases,
  };
  await fs.mkdir(path.dirname(out), { recursive: true });
  await fs.writeFile(out, JSON.stringify(fixture, null, 1) + '\n', 'utf8');
  console.log(`fixture: ${out}`);
  console.log(`alvos ${pkg.targets.length}, casos ${cases.length} (${cases.filter((c) => !/^[0-9a-f]{64}$/.test(c.result)).length} recusas), lacunas ${slotCases.length}`);
} finally {
  await fs.rm(dir, { recursive: true, force: true });
}
