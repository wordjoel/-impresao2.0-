import type { LabelObject, LabelTemplate, Symbology1D, Symbology2D } from '../model/objects';
import { createId } from '../model/objects';
import { canonicalJson, sha256Hex } from './versioning';

/**
 * Template como ARQUIVO (2026-10-19): levar uma etiqueta para outro PC, guardar cópia, e base do
 * passo 6 (template enviado ao app no coletor). A biblioteca do designer continua no navegador.
 *
 * O arquivo leva formato, versão do formato e SHA-256 do template (JSON canônico). Ao abrir, tudo
 * é conferido ANTES de aceitar: arquivo de outro programa, de versão mais nova, corrompido ou
 * editado à mão é recusado com o motivo — nunca abre uma etiqueta pela metade.
 */

export const TEMPLATE_FILE_FORMAT = 'athos-label-template';
export const TEMPLATE_FILE_VERSION = 1;

export interface TemplateFile {
  format: typeof TEMPLATE_FILE_FORMAT;
  formatVersion: number;
  exportedAt: string;
  sha256: string;
  template: LabelTemplate;
}

export class TemplateFileError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TemplateFileError';
  }
}

/** Arquivo autônomo: sem vínculo de herança (a variante já vem resolvida pelo designer). */
export async function templateToFile(template: LabelTemplate, now: Date = new Date()): Promise<string> {
  const standalone: LabelTemplate = { ...template, baseTemplateId: null };
  const file: TemplateFile = {
    format: TEMPLATE_FILE_FORMAT,
    formatVersion: TEMPLATE_FILE_VERSION,
    exportedAt: now.toISOString(),
    sha256: await sha256Hex(canonicalJson(standalone)),
    template: standalone,
  };
  return `${JSON.stringify(file, null, 2)}\n`;
}

const SYM_1D: Record<Symbology1D, true> = { CODE128: true, EAN13: true, UPCA: true, CODE39: true, EAN8: true, ITF14: true, GS1_128: true };
const SYM_2D: Record<Symbology2D, true> = { QR: true, DATAMATRIX: true, PDF417: true };

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v);

function checkObject(o: unknown, i: number): void {
  const where = `objeto ${i + 1}`;
  if (!isObj(o)) throw new TemplateFileError(`${where}: não é um objeto`);
  const name = typeof o.name === 'string' ? `"${o.name}"` : where;
  if (typeof o.id !== 'string' || o.id === '') throw new TemplateFileError(`${where}: sem id`);
  for (const k of ['x', 'y', 'width', 'height', 'rotation', 'zIndex']) {
    if (!num(o[k])) throw new TemplateFileError(`${name}: campo ${k} inválido`);
  }
  if (!((o.width as number) > 0 && (o.height as number) > 0)) throw new TemplateFileError(`${name}: largura e altura precisam ser positivas`);
  if (typeof o.visible !== 'boolean' || typeof o.locked !== 'boolean') throw new TemplateFileError(`${name}: visible/locked inválidos`);
  switch (o.type) {
    case 'TEXT':
      if (typeof o.content !== 'string' || !isObj(o.font) || !num(o.font.sizeMm)) throw new TemplateFileError(`${name}: texto incompleto`);
      return;
    case 'BARCODE_1D':
      if (typeof o.symbology !== 'string' || !(o.symbology in SYM_1D)) throw new TemplateFileError(`${name}: simbologia "${String(o.symbology)}" desconhecida`);
      if (typeof o.content !== 'string') throw new TemplateFileError(`${name}: código sem conteúdo`);
      return;
    case 'BARCODE_2D':
      if (typeof o.symbology !== 'string' || !(o.symbology in SYM_2D)) throw new TemplateFileError(`${name}: simbologia "${String(o.symbology)}" desconhecida`);
      if (typeof o.content !== 'string') throw new TemplateFileError(`${name}: código sem conteúdo`);
      return;
    case 'IMAGE':
      if (typeof o.src !== 'string') throw new TemplateFileError(`${name}: imagem sem origem`);
      if (o.mono !== null && !(isObj(o.mono) && num(o.mono.width) && num(o.mono.height) && typeof o.mono.data === 'string')) {
        throw new TemplateFileError(`${name}: imagem monocromática inválida`);
      }
      return;
    case 'BOX':
      if (!num(o.borderMm) || typeof o.filled !== 'boolean') throw new TemplateFileError(`${name}: caixa incompleta`);
      return;
    default:
      throw new TemplateFileError(`${name}: tipo "${String(o.type)}" desconhecido`);
  }
}

/**
 * Abre um arquivo de template. Devolve o template com id NOVO (nunca sobrescreve um template da
 * biblioteca); ids dos objetos são mantidos.
 */
export async function templateFromFile(text: string): Promise<LabelTemplate> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text.replace(/^﻿/, ''));
  } catch {
    throw new TemplateFileError('Arquivo não é JSON válido');
  }
  if (!isObj(parsed) || parsed.format !== TEMPLATE_FILE_FORMAT) throw new TemplateFileError('Arquivo não é um template do designer de etiquetas');
  if (!num(parsed.formatVersion)) throw new TemplateFileError('Versão do formato ausente');
  if ((parsed.formatVersion as number) > TEMPLATE_FILE_VERSION) {
    throw new TemplateFileError(`Arquivo feito numa versão mais nova do designer (formato ${String(parsed.formatVersion)}); atualize o designer`);
  }
  const t = parsed.template;
  if (!isObj(t)) throw new TemplateFileError('Arquivo sem template');
  if (typeof t.name !== 'string') throw new TemplateFileError('Template sem nome');
  if (!num(t.widthMm) || !num(t.heightMm) || !((t.widthMm as number) > 0 && (t.heightMm as number) > 0)) {
    throw new TemplateFileError('Tamanho da etiqueta inválido');
  }
  if (!num(t.dpi) || !((t.dpi as number) > 0)) throw new TemplateFileError('DPI inválido');
  if (!Array.isArray(t.objects)) throw new TemplateFileError('Template sem lista de objetos');
  t.objects.forEach(checkObject);
  const ids = new Set((t.objects as { id: string }[]).map((o) => o.id));
  if (ids.size !== t.objects.length) throw new TemplateFileError('Há objetos com id repetido');

  const expected = typeof parsed.sha256 === 'string' ? parsed.sha256 : '';
  const actual = await sha256Hex(canonicalJson(t));
  if (expected !== actual) throw new TemplateFileError('Arquivo alterado ou corrompido: o SHA-256 não confere');

  const template = t as unknown as LabelTemplate;
  return { ...template, id: createId(), baseTemplateId: null, objects: template.objects.map((o) => ({ ...o }) as LabelObject) };
}
