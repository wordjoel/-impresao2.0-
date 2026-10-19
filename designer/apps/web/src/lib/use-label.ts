import { useMemo } from 'react';
import { resolveLabel, type DataRow, type LabelTemplate, type ResolvedLabel } from '@athos-label/engine';
import { useDesigner, usePreviewRow } from '../store';
import { appMeasurer } from './measure';

/** Resolve o template com a linha de prévia: o mesmo resultado que vai para SVG/PDF/impressora. */
export function useResolvedLabel(template?: LabelTemplate, row?: DataRow): ResolvedLabel {
  const current = useDesigner((s) => s.template);
  const brand = useDesigner((s) => s.brand);
  const index = useDesigner((s) => s.previewIndex);
  const previewRow = usePreviewRow();
  const t = template ?? current;
  const r = row ?? previewRow;
  return useMemo(
    () => resolveLabel(t, r, { dpi: t.dpi, brand, measurer: appMeasurer, index }),
    [t, r, brand, index],
  );
}
