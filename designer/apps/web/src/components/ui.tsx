import { useEffect, useState, type ReactNode } from 'react';

/**
 * Número com rascunho local: grava ao sair do campo ou com Enter. Digitar "12,5" não cria
 * quatro passos no desfazer, e um valor inválido volta ao anterior em vez de quebrar o objeto.
 */
export function Num(props: {
  label: string;
  value: number;
  onCommit(v: number): void;
  step?: number;
  min?: number;
  max?: number;
  suffix?: string;
  hint?: string;
}) {
  const [draft, setDraft] = useState(String(props.value));
  useEffect(() => setDraft(String(props.value)), [props.value]);
  const commit = () => {
    const v = Number(draft.replace(',', '.'));
    if (!Number.isFinite(v) || (props.min !== undefined && v < props.min) || (props.max !== undefined && v > props.max)) {
      setDraft(String(props.value));
      return;
    }
    if (v !== props.value) props.onCommit(v);
  };
  return (
    <label className="field">
      <span>{props.label}</span>
      <span className="input-wrap">
        <input
          inputMode="decimal"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => e.key === 'Enter' && (e.currentTarget as HTMLInputElement).blur()}
        />
        {props.suffix && <em>{props.suffix}</em>}
      </span>
      {props.hint && <small>{props.hint}</small>}
    </label>
  );
}

/** Texto com rascunho local (mesmo motivo do Num). */
export function Text(props: { label: string; value: string; onCommit(v: string): void; multiline?: boolean; placeholder?: string; mono?: boolean }) {
  const [draft, setDraft] = useState(props.value);
  useEffect(() => setDraft(props.value), [props.value]);
  const commit = () => draft !== props.value && props.onCommit(draft);
  return (
    <label className="field wide">
      <span>{props.label}</span>
      {props.multiline ? (
        <textarea className={props.mono ? 'mono' : ''} rows={3} value={draft} placeholder={props.placeholder} onChange={(e) => setDraft(e.target.value)} onBlur={commit} />
      ) : (
        <input
          className={props.mono ? 'mono' : ''}
          value={draft}
          placeholder={props.placeholder}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => e.key === 'Enter' && (e.currentTarget as HTMLInputElement).blur()}
        />
      )}
    </label>
  );
}

export function Select<T extends string | number>(props: { label: string; value: T; options: readonly (readonly [T, string])[]; onChange(v: T): void }) {
  return (
    <label className="field">
      <span>{props.label}</span>
      <select
        value={String(props.value)}
        onChange={(e) => {
          const hit = props.options.find(([v]) => String(v) === e.target.value);
          if (hit) props.onChange(hit[0]);
        }}
      >
        {props.options.map(([v, l]) => (
          <option key={String(v)} value={String(v)}>
            {l}
          </option>
        ))}
      </select>
    </label>
  );
}

export function Check(props: { label: string; checked: boolean; onChange(v: boolean): void; title?: string }) {
  return (
    <label className="check" title={props.title}>
      <input type="checkbox" checked={props.checked} onChange={(e) => props.onChange(e.target.checked)} /> {props.label}
    </label>
  );
}

export function Section(props: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="section">
      <header>
        <h3>{props.title}</h3>
        {props.actions}
      </header>
      {props.children}
    </section>
  );
}

export function Notice(props: { kind: 'info' | 'warn' | 'error' | 'ok'; children: ReactNode }) {
  return <div className={`notice ${props.kind}`}>{props.children}</div>;
}

export const slug = (s: string) =>
  s
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/[^\w]+/g, '-')
    .replace(/^-|-$/g, '')
    .toLowerCase() || 'etiqueta';
