import type { AuditEntry, DataRow, LabelTemplate, PrinterProfile, PrinterStatus, Routing } from '@athos-label/engine';

export interface JobSummary {
  id: string;
  status: 'QUEUED' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  sentLabels: number;
  totalLabels: number;
  lastError: string | null;
  user: string;
  templateVersion: number;
  createdAt: string;
  updatedAt: string;
  groups: { printerId: string; total: number; sent: number }[];
}

export interface PrintServerConfig {
  url: string;
  token: string;
}

/** Cliente do print-server local (apps/print-server). */
export class PrintClient {
  constructor(private readonly cfg: PrintServerConfig) {}

  private async call<T>(method: string, path: string, body?: unknown): Promise<T> {
    let res: Response;
    try {
      res = await fetch(this.cfg.url.replace(/\/$/, '') + path, {
        method,
        headers: { Authorization: `Bearer ${this.cfg.token}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw new Error(`Servidor de impressão não responde em ${this.cfg.url}. Ele está rodando (npm run print-server)?`);
    }
    const json = (await res.json().catch(() => ({}))) as { error?: string };
    if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
    return json as T;
  }

  health() {
    return this.call<{ ok: boolean }>('GET', '/api/health');
  }
  printers() {
    return this.call<PrinterProfile[]>('GET', '/api/printers');
  }
  savePrinters(list: PrinterProfile[]) {
    return this.call<PrinterProfile[]>('PUT', '/api/printers', list);
  }
  status(id: string) {
    return this.call<{ status: PrinterStatus }>('GET', `/api/printers/${encodeURIComponent(id)}/status`);
  }
  createJob(req: {
    template: LabelTemplate;
    templateVersion: number;
    templateHash: string;
    rows: DataRow[];
    copies: number;
    chunkSize: number;
    routing: Routing;
    user: string;
  }) {
    return this.call<JobSummary>('POST', '/api/jobs', req);
  }
  jobs() {
    return this.call<JobSummary[]>('GET', '/api/jobs');
  }
  resume(id: string) {
    return this.call<JobSummary>('POST', `/api/jobs/${id}/resume`);
  }
  cancel(id: string) {
    return this.call<{ ok: boolean }>('POST', `/api/jobs/${id}/cancel`);
  }
  audit(jobId: string) {
    return this.call<AuditEntry[]>('GET', `/api/audit?jobId=${encodeURIComponent(jobId)}`);
  }
}
