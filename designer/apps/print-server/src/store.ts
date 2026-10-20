import { promises as fs } from 'node:fs';
import path from 'node:path';
import type { AuditEntry, JobState, LabelTemplate, PrinterProfile, SequenceCounter } from '@athos-label/engine';

/**
 * Persistência em arquivos (sem banco): checkpoint do job em JSON com gravação atômica
 * (temporário + rename) e trilha de auditoria append-only em JSONL, uma linha por etiqueta.
 */
export interface JobRecord {
  state: JobState;
  template: LabelTemplate;
}

export class FileStore {
  constructor(readonly dir: string) {}

  private file(...p: string[]) {
    return path.join(this.dir, ...p);
  }

  async init(): Promise<void> {
    await fs.mkdir(this.file('jobs'), { recursive: true });
  }

  private seq = 0;

  private async writeAtomic(file: string, data: string): Promise<void> {
    const tmp = `${file}.${process.pid}.${++this.seq}.tmp`;
    await fs.writeFile(tmp, data, 'utf8');
    // No Windows, rename por cima de um arquivo que alguém está lendo falha com EPERM/EBUSY
    // (o designer consulta o andamento enquanto o job grava o checkpoint). Tenta de novo.
    for (let attempt = 0; ; attempt++) {
      try {
        await fs.rename(tmp, file);
        return;
      } catch (e) {
        const code = (e as NodeJS.ErrnoException).code;
        if ((code !== 'EPERM' && code !== 'EBUSY' && code !== 'EACCES') || attempt >= 20) {
          await fs.rm(tmp, { force: true });
          throw e;
        }
        await new Promise((r) => setTimeout(r, 10 + attempt * 10));
      }
    }
  }

  async readJson<T>(file: string, fallback: T): Promise<T> {
    try {
      return JSON.parse(await fs.readFile(file, 'utf8')) as T;
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return fallback;
      throw e;
    }
  }

  printers(): Promise<PrinterProfile[]> {
    return this.readJson(this.file('printers.json'), []);
  }

  savePrinters(p: PrinterProfile[]): Promise<void> {
    return this.writeAtomic(this.file('printers.json'), JSON.stringify(p, null, 2));
  }

  /** Contadores de numeração contínua, um por templateId (passo 4). */
  counters(): Promise<Record<string, SequenceCounter>> {
    return this.readJson(this.file('counters.json'), {});
  }

  saveCounters(c: Record<string, SequenceCounter>): Promise<void> {
    return this.writeAtomic(this.file('counters.json'), JSON.stringify(c, null, 2));
  }

  saveJob(r: JobRecord): Promise<void> {
    if (!/^[\w-]+$/.test(r.state.spec.id)) throw new Error('id de job inválido');
    return this.writeAtomic(this.file('jobs', `${r.state.spec.id}.json`), JSON.stringify(r));
  }

  async job(id: string): Promise<JobRecord | null> {
    if (!/^[\w-]+$/.test(id)) return null;
    return this.readJson<JobRecord | null>(this.file('jobs', `${id}.json`), null);
  }

  async jobs(): Promise<JobRecord[]> {
    const names = await fs.readdir(this.file('jobs'));
    const out: JobRecord[] = [];
    for (const n of names.filter((n) => n.endsWith('.json'))) {
      const r = await this.job(n.slice(0, -5));
      if (r) out.push(r);
    }
    return out.sort((a, b) => b.state.spec.createdAt.localeCompare(a.state.spec.createdAt));
  }

  appendAudit(entries: AuditEntry[]): Promise<void> {
    if (entries.length === 0) return Promise.resolve();
    return fs.appendFile(this.file('audit.jsonl'), entries.map((e) => JSON.stringify(e)).join('\n') + '\n', 'utf8');
  }

  async audit(jobId?: string): Promise<AuditEntry[]> {
    let text: string;
    try {
      text = await fs.readFile(this.file('audit.jsonl'), 'utf8');
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return [];
      throw e;
    }
    const all = text.split('\n').filter(Boolean).map((l) => JSON.parse(l) as AuditEntry);
    return jobId ? all.filter((e) => e.jobId === jobId) : all;
  }
}
