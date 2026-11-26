import { spawn, type ChildProcess } from 'node:child_process'
import { createHash, X509Certificate } from 'node:crypto'
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { chromium, type Browser, type BrowserContext, type Page } from '@playwright/test'

// Playwright's normal focus emulation keeps automated tabs visible. This
// separate native default context verifies real visibility without overriding
// document state or changing the main desktop/Pixel 7 contexts.
interface Options { baseURL: string; viewport: { width: number; height: number } }
interface NativeVisibilityBrowser { context: BrowserContext; page: Page; dispose: () => Promise<void> }
const failure = 'The isolated native visibility browser could not start safely.'
const delay = (milliseconds: number) => new Promise<void>((resolve) => setTimeout(resolve, milliseconds))
const exited = (child: ChildProcess) => child.exitCode !== null || child.signalCode !== null

export async function startNativeVisibilityBrowser({ baseURL, viewport }: Options): Promise<NativeVisibilityBrowser> {
  let target: URL
  try { target = new URL(baseURL) } catch { throw new Error(failure) }
  if (target.protocol !== 'https:' || target.username || target.password || !Number.isSafeInteger(viewport.width) || !Number.isSafeInteger(viewport.height) || viewport.width < 1 || viewport.height < 1) throw new Error(failure)
  let profile: string | undefined
  let browser: Browser | undefined, child: ChildProcess | undefined, launchFailed = false, disposed = false
  const dispose = async () => {
    if (disposed) return; disposed = true
    if (browser) await Promise.race([browser.close().catch(() => undefined), delay(2_000)])
    if (child?.pid) {
      // detached:true creates this browser's own process group. Never target a
      // pre-existing browser, the caller's process group, or another profile.
      try { process.kill(-child.pid, 'SIGTERM') } catch { /* Already exited. */ }
      const deadline = Date.now() + 2_000
      while (!exited(child) && Date.now() < deadline) await delay(30)
      try { process.kill(-child.pid, 'SIGKILL') } catch { /* Group already gone. */ }
      const killedDeadline = Date.now() + 2_000
      while (!exited(child) && Date.now() < killedDeadline) await delay(30)
    }
    try { if (profile) rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }) } catch { throw new Error('The isolated native visibility browser cleanup did not complete.') }
  }
  try {
    profile = mkdtempSync(join(tmpdir(), 'xk-m406-native-')); chmodSync(profile, 0o700)
    const args = ['--no-sandbox', '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--disable-component-update', '--disable-sync', '--disable-dev-shm-usage', '--disable-gpu', '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0', `--user-data-dir=${profile}`, `--window-size=${viewport.width},${viewport.height}`]
    if (['localhost', '127.0.0.1', '[::1]'].includes(target.hostname)) {
      const certificate = new X509Certificate(readFileSync('infrastructure/dev-tls/localhost.crt'))
      const publicSpki = certificate.publicKey.export({ type: 'spki', format: 'der' })
      const pin = createHash('sha256').update(publicSpki).digest('base64')
      // Only this generated DEV public key is trusted as a local exception.
      // No global ignore-certificate-errors or weakened remote TLS is used.
      args.push(`--ignore-certificate-errors-spki-list=${pin}`)
    }
    args.push('about:blank')
    const authority = join(profile, 'native.Xauthority'); writeFileSync(authority, '', { mode: 0o600 }); chmodSync(authority, 0o600)
    // A private X server isolates actual native focus from both CI workers.
    // The detached wrapper, Xvfb and Chrome share only this owned group; the
    // caller's DISPLAY and emulated desktop/Pixel 7 browsers are unchanged.
    child = spawn('xvfb-run', ['--auto-servernum', `--auth-file=${authority}`, '--server-args=-screen 0 1920x1080x24 -nolisten tcp', chromium.executablePath(), ...args], { stdio: 'ignore', detached: true })
    child.on('error', () => { launchFailed = true })
    let endpoint: string | undefined
    const deadline = Date.now() + 10_000
    while (Date.now() < deadline && !launchFailed && !exited(child)) {
      try {
        const port = readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0]
        if (/^[1-9][0-9]{0,4}$/.test(port) && Number(port) <= 65535) { endpoint = `http://127.0.0.1:${port}`; break }
      } catch { /* Chrome has not written its private ephemeral endpoint yet. */ }
      await delay(30)
    }
    if (!endpoint || launchFailed || exited(child)) throw new Error(failure)
    browser = await chromium.connectOverCDP(endpoint, { noDefaults: true, isLocal: true, timeout: 15_000 })
    const context = browser.contexts()[0]; if (!context) throw new Error(failure)
    // noDefaults applies to the existing default context. Creating another
    // BrowserContext would re-enable the focus override and defeat this proof.
    const page = context.pages()[0] ?? await context.newPage()
    await page.setViewportSize(viewport)
    return { context, page, dispose }
  } catch {
    await dispose()
    // Profile paths, cookies and the browser-control endpoint are private.
    throw new Error(failure)
  }
}

interface NativeCredential { id: string; username: string; password: string }
export interface NativeLoginProof { visible: boolean; hasFocus: boolean; formsubmit: boolean; status: boolean; role: boolean; account: boolean; ready: boolean }

// Native contexts have no emulated focus. Observe the real UI login before
// asserting, and redact a failed login before any error-context can snapshot it.
export async function nativeSignIn(page: Page, credential: NativeCredential, fillSecret: (locator: import('@playwright/test').Locator, value: string) => Promise<void>): Promise<NativeLoginProof> {
  const proof: NativeLoginProof = { visible: false, hasFocus: false, formsubmit: false, status: false, role: false, account: false, ready: false }
  const origin = new URL(page.url()).origin
  let settle!: () => void, timer: ReturnType<typeof setTimeout> | undefined
  const responseReady = new Promise<void>((resolve) => { settle = resolve })
  const requestListener = (request: import('@playwright/test').Request) => { const url = new URL(request.url()); if (url.origin === origin && url.pathname === '/api/v1/auth/login' && request.method() === 'POST') proof.formsubmit = true }
  const responseListener = (response: import('@playwright/test').Response) => {
    const url = new URL(response.url()); if (url.origin !== origin || url.pathname !== '/api/v1/auth/login' || response.request().method() !== 'POST') return
    void response.json().then((body: { code?: unknown; data?: { role?: unknown; id?: unknown } }) => { proof.status = response.status() === 200 && body.code === '0000'; proof.role = body.data?.role === 'DRIVER'; proof.account = body.data?.id === credential.id; settle() }).catch(() => settle())
  }
  const waitFor = async (condition: () => Promise<boolean>, milliseconds: number) => { const deadline = Date.now() + milliseconds; while (Date.now() < deadline) { if (await condition()) return true; await delay(30) }; return false }
  try {
    await page.bringToFront()
    await waitFor(() => page.evaluate(() => document.visibilityState === 'visible' && document.hasFocus()), 2_000)
    proof.visible = await page.evaluate(() => document.visibilityState === 'visible'); proof.hasFocus = await page.evaluate(() => document.hasFocus())
    if (!proof.visible || !proof.hasFocus) return proof
    await page.getByLabel('帳號', { exact: true }).fill(credential.username)
    await fillSecret(page.getByLabel('密碼', { exact: true }), credential.password)
    page.on('request', requestListener); page.on('response', responseListener); timer = setTimeout(settle, 10_000)
    const button = page.getByRole('button', { name: '登入', exact: true })
    await page.bringToFront(); await button.focus(); proof.hasFocus = await page.evaluate(() => document.hasFocus())
    if (!proof.hasFocus) return proof
    await button.press('Enter'); await responseReady
    if (proof.formsubmit && proof.status && proof.role && proof.account) proof.ready = await waitFor(() => page.getByRole('button', { name: '登出', exact: true }).isVisible(), 5_000)
    return proof
  } catch { return proof } finally {
    if (timer) clearTimeout(timer); page.off('request', requestListener); page.off('response', responseListener)
    if (!proof.ready && !page.isClosed()) {
      // Failure-only redaction, after recording booleans from the real attempt.
      // Do not leave a password textbox for automatic ARIA error attachments.
      try { await page.locator('input[type="password"]').evaluateAll((inputs) => { for (const node of inputs) { const input = node as HTMLInputElement; input.value = ''; input.removeAttribute('value') } }) } catch { /* The page may already have retired. */ }
      try { await page.goto('about:blank', { waitUntil: 'commit', timeout: 5_000 }) } catch { /* Its owning browser is always disposed by the caller. */ }
    }
  }
}
