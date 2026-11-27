import { defineConfig, devices } from '@playwright/test'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const artifactRoot = join(root, 'artifacts/showcase-demo')
const supplied = process.env.SHOWCASE_BASE_URL
const baseURL = supplied ?? 'http://127.0.0.1:4197/logistics/'
const url = new URL(baseURL)
if (url.username || url.password || url.search || url.hash || url.pathname !== '/logistics/'
  || !(url.protocol === 'https:' && url.hostname === 'showcase.xplorekaleidoscope.com'
    || url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))) throw new Error('Only the isolated local or actual Showcase URL is allowed')

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.mjs',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 8_000 },
  outputDir: join(artifactRoot, 'test-results'),
  reporter: [['list'], ['json', { outputFile: join(artifactRoot, 'results.json') }]],
  use: { baseURL, screenshot: 'off', video: 'off', trace: 'off', serviceWorkers: 'block', reducedMotion: 'reduce' },
  webServer: supplied ? undefined : { command: 'node tests/showcase/server.mjs', cwd: root, url: baseURL, reuseExistingServer: false, timeout: 20_000 },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
})
