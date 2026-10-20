import { useMemo, useRef, useState } from 'react';
import { diffTemplates, resolveLabel, TemplateFileError, templateFromFile, templateToFile, type LabelTemplate } from '@athos-label/engine';
import { labelToSvg } from '@athos-label/exporters';
import { useDesigner, usePreviewRow } from '../store';
import { downloadBlob, readFileAsText } from '../lib/persist';
import { appMeasurer } from '../lib/measure';
import { Notice, Section, Text } from '../components/ui';

type Option = { key: string; label: string; template: LabelTemplate };

/** Biblioteca, versões (v1, v2...), variantes com herança e comparação A/B lado a lado. */
export function TemplatesPanel() {
  const template = useDesigner((s) => s.template);
  const library = useDesigner((s) => s.library);
  const st = useDesigner.getState();
  const [note, setNote] = useState('');
  const [author, setAuthor] = useState(() => localStorage.getItem('athos-label:author') ?? '');
  const [variantName, setVariantName] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  const entry = library[template.id];
  const versions = entry?.history.versions ?? [];
  const base = template.baseTemplateId ? library[template.baseTemplateId] : null;

  const save = async () => {
    localStorage.setItem('athos-label:author', author);
    const v = await st.saveVersion(note || 'sem descrição', author || 'anônimo');
    setMsg(v ? `Versão v${v.version} gravada (${v.hash.slice(0, 10)}).` : 'Nada mudou desde a última versão: nenhuma versão nova.');
    setNote('');
  };

  // Opções do A/B: o template atual, cada versão dele e os outros templates da biblioteca.
  const options = useMemo<Option[]>(() => {
    const out: Option[] = [{ key: 'current', label: 'Atual (não salvo)', template }];
    for (const v of [...versions].reverse()) out.push({ key: `v${v.version}`, label: `v${v.version} — ${v.note}`, template: v.snapshot });
    for (const e of Object.values(library)) {
      if (e.id === template.id) continue;
      const t = st.resolveEntry(e.id);
      if (t) out.push({ key: `t:${e.id}`, label: `${e.name}${e.variant ? ' (variante)' : ''}`, template: t });
    }
    return out;
  }, [template, versions, library, st]);

  return (
    <>
      <Section title="Templates" actions={<button className="link" onClick={st.newTemplate}>+ Novo</button>}>
        {base && (
          <Notice kind="info">
            Variante de <strong>{base.name}</strong>: guarda só o que muda. Alterações na base chegam aqui, exceto nos campos alterados nesta variante.
          </Notice>
        )}
        <ul className="library">
          {Object.values(library).map((e) => (
            <li key={e.id} className={e.id === template.id ? 'active' : ''}>
              <button className="layer-name" onClick={() => st.openTemplate(e.id)}>
                {e.variant ? '↳ ' : ''}
                {e.name}
                <span className="muted"> {e.history.versions.length ? `v${e.history.versions.length}` : 'sem versão'}</span>
              </button>
              {confirmDelete === e.id ? (
                <>
                  <button className="danger" onClick={() => (st.deleteTemplate(e.id), setConfirmDelete(null))}>
                    Confirmar
                  </button>
                  <button onClick={() => setConfirmDelete(null)}>Não</button>
                </>
              ) : (
                <button
                  className="icon-btn"
                  title={Object.values(library).some((x) => x.variant?.baseTemplateId === e.id) ? 'Tem variantes: exclua-as antes' : 'Excluir'}
                  disabled={Object.keys(library).length < 2 || Object.values(library).some((x) => x.variant?.baseTemplateId === e.id)}
                  onClick={() => setConfirmDelete(e.id)}
                >
                  🗑
                </button>
              )}
            </li>
          ))}
        </ul>
        <div className="row bottom">
          <Text label="Nova variante deste template" value={variantName} onCommit={setVariantName} placeholder="Ex.: Etiqueta de Promoção" />
          <button disabled={!variantName.trim()} onClick={() => (st.createVariant(variantName.trim()), setVariantName(''))}>
            Criar
          </button>
        </div>
      </Section>

      <TemplateFileSection />

      <Section title={`Versões de "${template.name}"`}>
        <div className="grid2">
          <Text label="O que mudou" value={note} onCommit={setNote} placeholder="Ex.: preço maior" />
          <Text label="Autor" value={author} onCommit={setAuthor} />
        </div>
        <button className="primary" onClick={() => void save()}>
          Salvar versão
        </button>
        {msg && <Notice kind="info">{msg}</Notice>}
        <ul className="versions">
          {[...versions].reverse().map((v) => (
            <li key={v.version}>
              <strong>v{v.version}</strong> {new Date(v.createdAt).toLocaleString('pt-BR')} · {v.author} · {v.note}
              <code title={v.hash}>{v.hash.slice(0, 8)}</code>
              <button className="link" onClick={() => st.restoreVersion(v)}>
                restaurar
              </button>
            </li>
          ))}
        </ul>
      </Section>

      <ABCompare options={options} />
    </>
  );
}

/**
 * Exportar/abrir template como arquivo `.athoslabel.json` (2026-10-19). O arquivo leva SHA-256;
 * ao abrir, `templateFromFile` confere tudo e recusa com o motivo, sem abrir etiqueta pela metade.
 */
function TemplateFileSection() {
  const template = useDesigner((s) => s.template);
  const importTemplate = useDesigner((s) => s.importTemplate);
  const file = useRef<HTMLInputElement>(null);
  const [result, setResult] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);

  const exportFile = async () => {
    const name = template.name.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^\w-]+/g, '-').replace(/^-+|-+$/g, '') || 'etiqueta';
    downloadBlob(await templateToFile(template), `${name}.athoslabel.json`, 'application/json');
    setResult({ kind: 'ok', text: `Arquivo exportado: ${name}.athoslabel.json` });
  };

  const openFile = async (file: File | undefined) => {
    if (!file) return;
    try {
      const t = await templateFromFile(await readFileAsText(file));
      importTemplate(t);
      setResult({ kind: 'ok', text: `"${t.name}" aberto de ${file.name} (${t.objects.length} objetos) e adicionado à biblioteca.` });
    } catch (e) {
      setResult({ kind: 'error', text: `Não foi possível abrir ${file.name}: ${e instanceof TemplateFileError ? e.message : String(e)}` });
    }
  };

  return (
    <Section title="Arquivo">
      <div className="row">
        <button onClick={() => void exportFile()}>Exportar arquivo</button>
        <button onClick={() => file.current?.click()}>Abrir arquivo…</button>
        <input
          ref={file}
          type="file"
          accept=".json,application/json"
          hidden
          onChange={(e) => {
            void openFile(e.target.files?.[0]);
            e.target.value = '';
          }}
        />
      </div>
      {result && <Notice kind={result.kind}>{result.text}</Notice>}
    </Section>
  );
}

function ABCompare({ options }: { options: Option[] }) {
  const row = usePreviewRow();
  const brand = useDesigner((s) => s.brand);
  const [a, setA] = useState(options[1]?.key ?? 'current');
  const [b, setB] = useState('current');
  const A = options.find((o) => o.key === a) ?? options[0]!;
  const B = options.find((o) => o.key === b) ?? options[0]!;
  const svg = (t: LabelTemplate) =>
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent(labelToSvg(resolveLabel(t, row, { dpi: t.dpi, brand, measurer: appMeasurer }), { showErrors: true }))}`;
  const diff = diffTemplates(A.template, B.template);

  return (
    <Section title="Comparar A/B">
      <p className="muted">As duas variações com a mesma linha de dados, antes de aprovar para produção.</p>
      <div className="ab">
        {([
          ['A', a, setA, A],
          ['B', b, setB, B],
        ] as const).map(([name, value, setter, opt]) => (
          <div key={name} className="ab-side">
            <select value={value} onChange={(e) => setter(e.target.value)} aria-label={`Variação ${name}`}>
              {options.map((o) => (
                <option key={o.key} value={o.key}>
                  {name}: {o.label}
                </option>
              ))}
            </select>
            <img src={svg(opt.template)} alt={`Prévia ${name}`} />
          </div>
        ))}
      </div>
      <p className="muted">
        Diferenças de A para B: {diff.added.length} adicionado(s), {diff.removed.length} removido(s), {diff.changed.length} alterado(s)
        {diff.sizeChanged ? ', tamanho diferente' : ''}.
        {diff.changed.slice(0, 6).map((c) => (
          <span key={c.after.id} className="chip">
            {c.after.name}: {c.fields.join(', ')}
          </span>
        ))}
      </p>
    </Section>
  );
}
