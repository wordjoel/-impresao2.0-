import { randomBytes } from 'node:crypto';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createPrintServer } from './server';
import { FileStore } from './store';

const port = Number(process.env.PORT ?? 7171);
const dataDir =
  process.env.DATA_DIR ?? path.join(process.env.LOCALAPPDATA ?? path.join(os.homedir(), '.local', 'share'), 'athos-label', 'print-server');
const origins = (process.env.ALLOWED_ORIGINS ?? 'http://localhost:5173,http://127.0.0.1:5173').split(',').map((s) => s.trim());

async function loadToken(): Promise<string> {
  const file = path.join(dataDir, 'token.txt');
  try {
    return (await fs.readFile(file, 'utf8')).trim();
  } catch {
    await fs.mkdir(dataDir, { recursive: true });
    const token = randomBytes(24).toString('hex');
    await fs.writeFile(file, token, { encoding: 'utf8', mode: 0o600 });
    return token;
  }
}

const token = await loadToken();
const server = await createPrintServer({
  store: new FileStore(dataDir),
  token,
  allowedOrigins: origins,
  log: (m) => console.log(`[${new Date().toISOString()}] ${m}`),
});

server.listen(port, '127.0.0.1', () => {
  console.log(`ATHOS Label print-server em http://127.0.0.1:${port}`);
  console.log(`Dados: ${dataDir}`);
  console.log(`Token (cole no designer, aba Imprimir): ${path.join(dataDir, 'token.txt')}`);
});
