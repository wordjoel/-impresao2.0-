import { describe, expect, it } from 'vitest';
import { createTemplate, createTextObject, type LabelTemplate } from '../src/model/objects';
import { addObject, removeObject, updateObject } from '../src/model/template-ops';
import { canonicalJson, commitVersion, diffTemplates, sha256Hex } from '../src/templates/versioning';
import { deriveVariant, resolveVariant } from '../src/templates/inheritance';

function base(): LabelTemplate {
  let t = { ...createTemplate(50, 30, 'Preço 50x30'), id: 'base' };
  t = addObject(t, createTextObject({ id: 'nome', content: '{{PRODUTO}}' }));
  t = addObject(t, createTextObject({ id: 'preco', content: '{{PRECO | currency}}', y: 10 }));
  return t;
}

describe('versionamento', () => {
  it('JSON canônico ignora a ordem das chaves', async () => {
    expect(canonicalJson({ b: 1, a: [1, { d: 2, c: 3 }] })).toBe('{"a":[1,{"c":3,"d":2}],"b":1}');
    expect(await sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });

  it('v1, v2... e gravar sem mudança não cria versão', async () => {
    let h = { templateId: 'base', versions: [] as never[] } as Parameters<typeof commitVersion>[0];
    const t = base();
    let r = await commitVersion(h, t, { author: 'ana', note: 'inicial' });
    expect(r.created?.version).toBe(1);
    h = r.history;
    r = await commitVersion(h, structuredClone(t), { author: 'ana', note: 'igual' });
    expect(r.created).toBeNull();
    r = await commitVersion(h, updateObject(t, 'nome', { x: 5 }), { author: 'bia', note: 'move' });
    expect(r.created?.version).toBe(2);
    expect(r.created?.hash).not.toBe(h.versions[0]!.hash);
  });

  it('diff aponta o campo alterado, o adicionado e o removido', () => {
    const a = base();
    const b = addObject(removeObject(updateObject(a, 'nome', { x: 9 }), 'preco'), createTextObject({ id: 'novo' }));
    const d = diffTemplates(a, b);
    expect(d.changed.map((c) => [c.after.id, c.fields])).toEqual([['nome', ['x']]]);
    expect(d.added.map((o) => o.id)).toEqual(['novo']);
    expect(d.removed.map((o) => o.id)).toEqual(['preco']);
  });
});

describe('herança de template', () => {
  it('variante guarda só a diferença e reconstrói o template editado', () => {
    const b = base();
    let edited: LabelTemplate = { ...b, id: 'promo', name: 'Promoção' };
    edited = updateObject(edited, 'preco', { content: 'DE {{PRECO_DE | currency}} POR {{PRECO | currency}}' });
    edited = addObject(edited, createTextObject({ id: 'selo', content: 'PROMOÇÃO' }));
    const v = deriveVariant(b, edited);
    expect(Object.keys(v.overrides)).toEqual(['preco']);
    expect(Object.keys(v.overrides.preco!)).toEqual(['content']);
    expect(v.added.map((o) => o.id)).toEqual(['selo']);
    expect(resolveVariant(b, v).objects).toEqual(edited.objects);
  });

  it('mudança na base chega na variante, menos o que ela sobrescreveu', () => {
    const b = base();
    const v = deriveVariant(b, updateObject({ ...b, id: 'promo' }, 'preco', { content: 'POR {{PRECO}}' }));
    const b2 = updateObject(updateObject(b, 'preco', { x: 20, content: 'outro' }), 'nome', { y: 7 });
    const r = resolveVariant(b2, v);
    const preco = r.objects.find((o) => o.id === 'preco')!;
    expect(preco.x).toBe(20);
    expect(preco.type === 'TEXT' && preco.content).toBe('POR {{PRECO}}');
    expect(r.objects.find((o) => o.id === 'nome')!.y).toBe(7);
  });
});
