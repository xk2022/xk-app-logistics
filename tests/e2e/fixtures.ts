import { closeSync, constants, fchmodSync, fstatSync, fsyncSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { randomBytes } from 'node:crypto'
import { expect, type Locator, type Page } from '@playwright/test'

export interface Credential { username: string; password: string }
interface AdmFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
  parameterKeys: { text: string; number: string; boolean: string }
}
interface CrmFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
}
interface TomFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
}
interface FmsFixture extends TomFixture {
  vehicleTypeCategoryId: string
  vehicleTypes: Record<'a' | 'b' | 'c', { id: string; code: string; label: string }>
}
interface DriverCredential extends Credential { id: string }
interface DispatchFixture {
  admin: DriverCredential
  dispatcher: DriverCredential
  driverA: DriverCredential
  driverB: DriverCredential
  scope: 'DESKTOP' | 'MOBILE'
  vehicleTypes: Record<'main' | 'trailer' | 'shared', { id: string; code: string; label: string }>
}
interface ExecutionFixture extends DispatchFixture { temporary: DriverCredential }
interface DriverFixture {
  admin: DriverCredential
  dispatcher: DriverCredential
  driverA: DriverCredential
  driverB: DriverCredential
  driverC: DriverCredential
  temporary: DriverCredential
  accountSearchPrefix: string
  licenseTypeCategoryId: string
  licenseTypes: Record<'a' | 'b', { id: string; code: string; label: string }>
}
interface ProjectFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
  adm?: AdmFixture
  crm?: CrmFixture
  tom?: TomFixture
  orderUi?: TomFixture
  fms?: FmsFixture
  driverFlow?: DriverFixture
  acceptance?: TomFixture
  dispatch?: DispatchFixture
  dispatchUi?: DispatchFixture
  execution?: ExecutionFixture
  progressEvidence?: ExecutionFixture
  exception?: ExecutionFixture
  completion?: ExecutionFixture
  closure?: ExecutionFixture
  executionUi?: ExecutionFixture
}

export interface M4IntegrationFixture {
  admin: DriverCredential
  controlAdmin: DriverCredential
  driver: DriverCredential
}

// This group exists once at the root, not once per browser project.
export function m4IntegrationFixture(): M4IntegrationFixture {
  const file = process.env.E2E_FIXTURE_PATH ?? '/tmp/xk-m2-e2e-fixture.json'
  let fixture: unknown
  try { fixture = JSON.parse(readFileSync(file, 'utf8')) as unknown } catch {
    throw new Error('Create the isolated test fixture before running the M4 integration drill.')
  }
  const invalid = () => new Error('M4 integration fixture is incomplete.')
  if (fixture === null || typeof fixture !== 'object' || Array.isArray(fixture)) throw invalid()
  const group = (fixture as Record<string, unknown>).m4Integration
  if (group === null || typeof group !== 'object' || Array.isArray(group) || Object.keys(group).sort().join() !== 'admin,controlAdmin,driver') throw invalid()
  const accounts = group as Record<string, unknown>
  const ids: string[] = []
  for (const key of ['admin', 'controlAdmin', 'driver']) {
    const account = accounts[key]
    if (account === null || typeof account !== 'object' || Array.isArray(account) || Object.keys(account).sort().join() !== 'id,password,username') throw invalid()
    const value = account as Record<string, unknown>
    if (!['id', 'username', 'password'].every((field) => typeof value[field] === 'string' && value[field].length > 0) || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value.id as string)) throw invalid()
    ids.push(value.id as string)
  }
  if (new Set(ids).size !== 3) throw invalid()
  return group as M4IntegrationFixture
}

export function fixtureFor(project: string): ProjectFixture {
  const file = process.env.E2E_FIXTURE_PATH ?? '/tmp/xk-m2-e2e-fixture.json'
  let fixture: unknown
  try { fixture = JSON.parse(readFileSync(file, 'utf8')) as unknown } catch {
    throw new Error('Create the isolated UPMS test fixture before running E2E.')
  }
  const group = (fixture as Record<string, ProjectFixture>)[project]
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driver']
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('UPMS test fixture is incomplete for this browser project.')
  return group
}

export function admFixtureFor(project: string): AdmFixture {
  const group = fixtureFor(project).adm
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driver']
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  }) || !['text', 'number', 'boolean'].every((key) => typeof group.parameterKeys?.[key as keyof AdmFixture['parameterKeys']] === 'string')) {
    throw new Error('ADM test fixture is incomplete for this browser project.')
  }
  return group
}

export function crmFixtureFor(project: string): CrmFixture {
  const group = fixtureFor(project).crm
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof CrmFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('CRM test fixture is incomplete for this browser project.')
  return group
}

export function tomFixtureFor(project: string): TomFixture {
  const group = fixtureFor(project).tom
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('TOM test fixture is incomplete for this browser project.')
  return group
}

export function orderUiFixtureFor(project: string): TomFixture {
  const group = fixtureFor(project).orderUi
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('Order UI test fixture is incomplete for this browser project.')
  return group
}

export function fmsFixtureFor(project: string): FmsFixture {
  const group = fixtureFor(project).fms
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  }) || typeof group.vehicleTypeCategoryId !== 'string' || !['a', 'b', 'c'].every((key) => {
    const type = group.vehicleTypes?.[key as 'a' | 'b' | 'c']
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('FMS test fixture is incomplete for this browser project.')
  return group
}

export function driverFixtureFor(project: string): DriverFixture {
  const group = fixtureFor(project).driverFlow
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'driverC', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'driverC' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || typeof group.accountSearchPrefix !== 'string' || typeof group.licenseTypeCategoryId !== 'string' || !['a', 'b'].every((key) => {
    const type = group.licenseTypes?.[key as 'a' | 'b']
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Driver test fixture is incomplete for this browser project.')
  return group
}

export function acceptanceFixtureFor(project: string): TomFixture {
  const group = fixtureFor(project).acceptance
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('Acceptance test fixture is incomplete for this browser project.')
  return group
}

export function dispatchFixtureFor(project: string): DispatchFixture {
  const group = fixtureFor(project).dispatch
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Dispatch test fixture is incomplete for this browser project.')
  return group
}

export function dispatchUiFixtureFor(project: string): DispatchFixture {
  const group = fixtureFor(project).dispatchUi
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Dispatch UI test fixture is incomplete for this browser project.')
  return group
}

export function executionFixtureFor(project: string): ExecutionFixture {
  const group = fixtureFor(project).execution
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Execution test fixture is incomplete for this browser project.')
  return group
}

export function progressEvidenceFixtureFor(project: string): ExecutionFixture {
  const group = fixtureFor(project).progressEvidence
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Progress/evidence test fixture is incomplete for this browser project.')
  return group
}

export function exceptionFixtureFor(project: string): ExecutionFixture {
  const group = fixtureFor(project).exception
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Exception test fixture is incomplete for this browser project.')
  return group
}

export function completionFixtureFor(project: string): ExecutionFixture {
  const group = fixtureFor(project).completion
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Completion test fixture is incomplete for this browser project.')
  return group
}

export function closureFixtureFor(project: string): ExecutionFixture {
  const group = fixtureFor(project).closure
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Closure test fixture is incomplete for this browser project.')
  return group
}

export function executionUiFixtureFor(project: string): ExecutionFixture {
  const group = fixtureFor(project).executionUi
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Driver execution UI test fixture is incomplete for this browser project.')
  return group
}

export async function changeInitialPassword(page: Page, currentPassword: string, newPassword: string): Promise<void> {
  await expect(page.getByRole('heading', { name: '首次登入，請先修改密碼' })).toBeVisible()
  await fillPassword(page.getByLabel('目前密碼', { exact: true }), currentPassword)
  await fillPassword(page.getByLabel('新密碼', { exact: true }), newPassword)
  await fillPassword(page.getByLabel('確認新密碼', { exact: true }), newPassword)
  await page.getByRole('button', { name: '確認修改密碼', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登入工作空間' })).toBeVisible()
}

const inventoryPause = new Int32Array(new SharedArrayBuffer(4))
const inventoryFailure = 'Synthetic passwords require a complete private secret inventory.'

function registerSyntheticPassword(password: string): void {
  const configured = process.env.M4_SECRET_INVENTORY_PATH
  if (!configured || !configured.trim()) throw new Error(inventoryFailure)
  const target = resolve(configured), lock = `${target}.lock`, pending = `${target}.pending`
  let lockDescriptor: number | undefined, sourceDescriptor: number | undefined, pendingDescriptor: number | undefined, directoryDescriptor: number | undefined, ownsPending = false
  const deadline = performance.now() + 5_000
  const close = (descriptor: number | undefined) => { if (descriptor !== undefined) { try { closeSync(descriptor) } catch { /* No values or raw filesystem errors are reported. */ } } }
  try {
    while (lockDescriptor === undefined) {
      if (performance.now() >= deadline) throw new Error(inventoryFailure)
      try { lockDescriptor = openSync(lock, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600); fchmodSync(lockDescriptor, 0o600) }
      catch (failure) {
        if ((failure as NodeJS.ErrnoException).code !== 'EEXIST' || performance.now() >= deadline) throw new Error(inventoryFailure)
        Atomics.wait(inventoryPause, 0, 0, Math.min(10, Math.max(1, deadline - performance.now())))
      }
    }
    sourceDescriptor = openSync(target, constants.O_RDONLY | constants.O_NOFOLLOW)
    const source = fstatSync(sourceDescriptor)
    if (!source.isFile() || (source.mode & 0o777) !== 0o600) throw new Error(inventoryFailure)
    const inventory: unknown = JSON.parse(readFileSync(sourceDescriptor, 'utf8'))
    if (inventory === null || typeof inventory !== 'object' || Array.isArray(inventory)) throw new Error(inventoryFailure)
    const object = inventory as Record<string, unknown>, generated = object._generatedSecrets
    if (generated !== undefined && !Array.isArray(generated)) throw new Error(inventoryFailure)
    object._generatedSecrets = [...(generated as unknown[] | undefined ?? []), { password }]
    pendingDescriptor = openSync(pending, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600); ownsPending = true; fchmodSync(pendingDescriptor, 0o600)
    writeFileSync(pendingDescriptor, JSON.stringify(object) + '\n', 'utf8'); fsyncSync(pendingDescriptor); closeSync(pendingDescriptor); pendingDescriptor = undefined
    renameSync(pending, target); ownsPending = false
    directoryDescriptor = openSync(dirname(target), constants.O_RDONLY | constants.O_DIRECTORY); fsyncSync(directoryDescriptor)
  } catch { throw new Error(inventoryFailure) } finally {
    close(sourceDescriptor); close(pendingDescriptor); close(directoryDescriptor)
    let cleanupFailed = false
    if (ownsPending) { try { unlinkSync(pending) } catch (failure) { if ((failure as NodeJS.ErrnoException).code !== 'ENOENT') cleanupFailed = true } }
    if (lockDescriptor !== undefined) { close(lockDescriptor); try { unlinkSync(lock) } catch (failure) { if ((failure as NodeJS.ErrnoException).code !== 'ENOENT') cleanupFailed = true } }
    if (cleanupFailed) throw new Error(inventoryFailure)
  }
}

export function syntheticPassword(): string {
  const password = randomBytes(24).toString('base64url')
  // Registration is synchronous and durable before any caller can use it.
  registerSyntheticPassword(password)
  return password
}

export async function fillPassword(locator: Locator, password: string): Promise<void> {
  await expect(locator).toBeVisible()
  await expect(locator).toHaveAttribute('type', 'password')
  // Keep credential values out of Playwright's action titles and HTML reports.
  await locator.evaluate((element, value) => {
    const input = element as HTMLInputElement
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }, password)
}

export async function signIn(page: Page, credential: Credential): Promise<void> {
  await page.getByLabel('帳號', { exact: true }).fill(credential.username)
  const password = page.getByLabel('密碼', { exact: true })
  await fillPassword(password, credential.password)
  await expect(page.getByRole('button', { name: '登入', exact: true })).toBeEnabled()
  await password.press('Enter')
}
