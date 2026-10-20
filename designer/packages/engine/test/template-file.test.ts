import { describe, expect, it } from 'vitest';
import {
  addObject,
  createBarcode1D,
  createBarcode2D,
  createBox,
  createTemplate,
  createTextObject,
  TEMPLATE_FILE_FORMAT,
  TemplateFileError,
  templateFromFile,
  templateToFile,
  type LabelTemplate,
} from '../src';

/** 2026-10-19 — template como arquivo: ida e volta sem perda; arquivo ruim recusado com motivo. */

function sample(): LabelTemplate {
  let t = createTemplate(60, 40, 'Preço Açúcar');
  t = addObject(t, createTextObject({ id: 'txt', content: '{{PRODUTO}}', x: 2, y: 2, width: 40, height: 6, hyphenate: true }));
  t = addObject(t, createBarcode1D({ id: 'gs1', symbology: 'GS1_128', content: '(01)10012345678902', x: 2, y: 10, width: 56, height: 12 }));
  t = addObject(t, createBarcode2D({ id: 'pdf', symbology: 'PDF417', content: '{{SKU}}', x: 2, y: 24, width: 40, height: 14 }));
  t = addObject(t, createBox({ id: 'box', x: 0.5, y: 0.5, width: 59, height: 39 }));
  return { ...t, baseTemplateId: 'algum-base' };
}

async function tamper(edit: (f: Record<string, any>) => void): Promise<string> {
  const f = JSON.parse(await templateToFile(sample()));
  edit(f);
  return JSON.stringify(f);
}

describe('ida e volta', () => {
  it('mesmo conteúdo, id novo, sem vínculo de herança', async () => {
    const original = sample();
    const back = await templateFromFile(await templateToFile(original));
    expect(back.id).not.toBe(original.id);
    expect(back.baseTemplateId).toBeNull();
    expect(back.name).toBe('Preço Açúcar');
    expect(back.objects).toEqual(original.objects);
    expect([back.widthMm, back.heightMm, back.dpi]).toEqual([60, 40, 203]);
  });

  it('cabeçalho do arquivo: formato, versão, data e SHA-256', async () => {
    const f = JSON.parse(await templateToFile(sample(), new Date('2026-10-19T12:00:00Z')));
    expect(f.format).toBe(TEMPLATE_FILE_FORMAT);
    expect(f.formatVersion).toBe(1);
    expect(f.exportedAt).toBe('2026-10-19T12:00:00.000Z');
    expect(f.sha256).toMatch(/^[0-9a-f]{64}$/);
  });

  it('aceita arquivo com BOM (Bloco de Notas do Windows)', async () => {
    await expect(templateFromFile(`﻿${await templateToFile(sample())}`)).resolves.toBeTruthy();
  });
});

describe('recusa com motivo', () => {
  const rejects = async (text: string, msg: RegExp) => {
    await expect(templateFromFile(text)).rejects.toThrow(TemplateFileError);
    await expect(templateFromFile(text)).rejects.toThrow(msg);
  };

  it('não-JSON e JSON de outro programa', async () => {
    await rejects('isto não é json', /JSON válido/);
    await rejects('{"nome":"x"}', /não é um template/);
  });

  it('versão de formato mais nova', async () => {
    await rejects(await tamper((f) => (f.formatVersion = 2)), /versão mais nova/);
  });

  it('editado à mão (SHA-256 não confere)', async () => {
    await rejects(await tamper((f) => (f.template.objects[0].x = 3)), /SHA-256/);
  });

  it('simbologia e tipo desconhecidos, medida inválida, id repetido', async () => {
    await rejects(await tamper((f) => (f.template.objects[1].symbology = 'AZTEC')), /simbologia "AZTEC"/);
    await rejects(await tamper((f) => (f.template.objects[3].type = 'CIRCLE')), /tipo "CIRCLE"/);
    await rejects(await tamper((f) => (f.template.objects[0].width = -1)), /positivas/);
    await rejects(await tamper((f) => (f.template.widthMm = 0)), /Tamanho/);
    await rejects(await tamper((f) => (f.template.objects[1].id = 'txt')), /id repetido/);
  });
});
