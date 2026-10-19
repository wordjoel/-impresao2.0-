import { useDesigner, useRows } from '../store';
import { useResolvedLabel } from '../lib/use-label';

/** Abaixo do canvas: qual linha de dados está na prévia e os avisos desta etiqueta. */
export function StatusBar() {
  const rows = useRows();
  const index = useDesigner((s) => s.previewIndex);
  const { setPreviewIndex, select } = useDesigner.getState();
  const label = useResolvedLabel();
  const issues = label.issues;

  return (
    <footer className="statusbar">
      <div className="row">
        <button disabled={index <= 0} onClick={() => setPreviewIndex(index - 1)} aria-label="Linha anterior">
          ◀
        </button>
        <span>
          Linha <strong>{rows.length ? index + 1 : 0}</strong> de {rows.length.toLocaleString('pt-BR')}
        </span>
        <button disabled={index >= rows.length - 1} onClick={() => setPreviewIndex(index + 1)} aria-label="Próxima linha">
          ▶
        </button>
      </div>
      <ul className="issues">
        {issues.length === 0 && <li className="ok">✓ Sem avisos nesta etiqueta</li>}
        {issues.slice(0, 6).map((i, k) => (
          <li key={k} className={i.severity}>
            <button className="link" disabled={!i.objectId} onClick={() => i.objectId && select([i.objectId])}>
              {i.severity === 'error' ? '⛔' : '⚠'} {i.message}
            </button>
          </li>
        ))}
        {issues.length > 6 && <li className="muted">+{issues.length - 6}</li>}
      </ul>
    </footer>
  );
}
