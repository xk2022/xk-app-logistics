import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile, readdir } from 'node:fs/promises'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const frontend = join(root, 'frontend')
const demoRoot = join(frontend, 'dist-showcase')
const normalRoot = join(frontend, 'dist')
const entryMarker = 'data-showcase-demo'

async function files(directory, base = directory) {
  const result = []
  for (const item of await readdir(directory, { withFileTypes: true })) {
    assert(!item.isSymbolicLink(), 'Build output must not contain symlinks')
    const path = join(directory, item.name)
    if (item.isDirectory()) result.push(...await files(path, base))
    else if (item.isFile()) result.push(relative(base, path).split('\\').join('/'))
  }
  return result.sort()
}

function attributes(tag) {
  return Object.fromEntries([...tag.matchAll(/([\w:-]+)\s*=\s*(["'])(.*?)\2/gs)]
    .map(([, key, , value]) => [key.toLowerCase(), value]))
}

export async function assertBuildSeparation({ baseline } = {}) {
  const provenance = JSON.parse(await readFile(join(demoRoot, 'showcase-build.json'), 'utf8'))
  assert.equal(provenance.entry, 'showcase/main.tsx')
  assert.equal(provenance.base, '/logistics/')
  assert(Array.isArray(provenance.modules) && provenance.modules.length > 0)
  assert.deepEqual(provenance.modules, [...new Set(provenance.modules)].sort(), 'Module provenance must be deterministic')
  assert(provenance.modules.includes('showcase/main.tsx'), 'The actual independent entry must be bundled')
  assert(provenance.modules.includes('showcase/demoFixtures.ts'), 'The actual synthetic fixture module must be bundled')
  for (const id of provenance.modules) {
    assert(typeof id === 'string' && id.length > 0 && !id.includes('\0') && !id.includes('\\')
      && !id.startsWith('/') && !/^[A-Za-z]:/.test(id) && !id.split('/').includes('..'), 'Module ids must be relative and normalized')
    assert(!/^[a-z][a-z\d+.-]*:/i.test(id) || id.startsWith('virtual:'), 'No absolute module URLs')
    assert(!/^src\/(?:App|main|api)\.tsx?$/.test(id), 'Demo must not import the production application or authentication entry')
    assert(!/^src\/.*(?:Api|Client)\.[cm]?[jt]sx?$/.test(id), 'Demo must not import product API clients')
    assert(!/(?:^|\/)(?:test|tests)(?:\/|$)|\.(?:test|spec)\.[cm]?[jt]sx?$|TestData\./.test(id), 'Test code must not ship in the demo')
  }

  const demoFiles = await files(demoRoot)
  assert(demoFiles.includes('index.html') && demoFiles.includes('showcase-build.json'))
  for (const path of demoFiles) {
    assert(/\.(?:html|js|css|json|svg|png|jpe?g|webp|woff2?|ico|txt)$/.test(path), 'Unexpected source or private build file')
    assert(!/(?:^|\/)(?:\.env|node_modules|\.git)(?:[./]|$)/.test(path), 'Private or source paths must not ship')
  }
  const html = await readFile(join(demoRoot, 'index.html'), 'utf8')
  const metas = [...html.matchAll(/<meta\b[^>]*>/gi)].map(([tag]) => attributes(tag))
  const robots = metas.filter(meta => meta.name?.toLowerCase() === 'robots')
  assert.equal(robots.length, 1)
  assert.deepEqual(robots[0].content.toLowerCase().split(/[\s,]+/).filter(Boolean).sort(), ['nofollow', 'noindex'])
  const policies = metas.filter(meta => meta['http-equiv']?.toLowerCase() === 'content-security-policy')
  assert.equal(policies.length, 1)
  const policy = policies[0].content.split(';').map(value => value.trim())
  for (const required of ["connect-src 'none'", "form-action 'none'", "base-uri 'none'", "object-src 'none'"]) assert(policy.includes(required), 'Missing offline containment policy')
  const links = [...html.matchAll(/<link\b[^>]*>/gi)].map(([tag]) => attributes(tag))
  const canonical = links.filter(link => link.rel === 'canonical')
  assert.equal(canonical.length, 1)
  assert.equal(canonical[0].href, 'https://showcase.xplorekaleidoscope.com/logistics/')
  for (const [, tag, content] of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    assert(attributes(tag).src?.startsWith('/logistics/assets/'), 'Only independent local scripts may execute')
    assert(!content.trim(), 'No inline executable script')
  }
  const javascript = (await Promise.all(demoFiles.filter(path => path.endsWith('.js')).map(path => readFile(join(demoRoot, path), 'utf8')))).join('\n')
  assert(javascript.includes(entryMarker), 'The independent demo UI must be present')
  assert(!/\/api\/v1(?:\/|["'])/.test(javascript), 'Product endpoints must not ship in the offline demo')

  const normalFiles = await files(normalRoot)
  const normalHashes = {}
  for (const path of normalFiles) {
    const bytes = await readFile(join(normalRoot, path))
    assert(!bytes.includes(Buffer.from(entryMarker)), 'The normal build must exclude the demo UI')
    assert(!bytes.includes(Buffer.from('showcase/main.tsx')), 'The normal build must exclude demo source')
    normalHashes[path] = createHash('sha256').update(bytes).digest('hex')
  }
  if (baseline) assert.deepEqual(normalHashes, JSON.parse(await readFile(baseline, 'utf8')), 'The normal production output must stay byte-identical')
  return { status: 'passed', modules: provenance.modules.length, demoFiles: demoFiles.length, normalFiles: normalFiles.length, normalByteBaseline: Boolean(baseline) }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const index = process.argv.indexOf('--baseline')
  const result = await assertBuildSeparation({ baseline: index >= 0 ? process.argv[index + 1] : undefined })
  process.stdout.write(JSON.stringify(result) + '\n')
}
