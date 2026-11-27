import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { dirname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const directory = resolve(dirname(fileURLToPath(import.meta.url)), '../../frontend/dist-showcase')
const port = Number(process.env.SHOWCASE_TEST_PORT ?? '4197')
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid Showcase test port')
const types = { html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', css: 'text/css; charset=utf-8', json: 'application/json', svg: 'image/svg+xml', png: 'image/png', jpg: 'image/jpeg', webp: 'image/webp', woff2: 'font/woff2', ico: 'image/x-icon' }
await stat(resolve(directory, 'index.html'))
const server = createServer(async (request, response) => {
  response.setHeader('Cache-Control', 'no-store')
  if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405).end(); return }
  try {
    const path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname)
    if (path === '/logistics') { response.writeHead(308, { Location: '/logistics/' }).end(); return }
    if (!path.startsWith('/logistics/') || path.includes('\\') || path.includes('\0')) { response.writeHead(404).end(); return }
    const relative = path.slice('/logistics/'.length)
    if (relative.split('/').some(part => part === '..')) { response.writeHead(404).end(); return }
    let file = resolve(directory, relative || 'index.html')
    if (!file.startsWith(directory + sep)) { response.writeHead(404).end(); return }
    if ((await stat(file)).isDirectory()) file = resolve(file, 'index.html')
    const bytes = await readFile(file)
    response.writeHead(200, { 'Content-Type': types[file.split('.').at(-1)] ?? 'application/octet-stream', 'X-Content-Type-Options': 'nosniff' })
    response.end(request.method === 'HEAD' ? undefined : bytes)
  } catch { response.writeHead(404).end() }
})
server.listen(port, '127.0.0.1', () => process.stdout.write(`Showcase test server ready on ${port}\n`))
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => server.close(() => process.exit(0)))
