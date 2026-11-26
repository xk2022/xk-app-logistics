import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { closureData, closureResult, closureSummary, closureWitness, confirmationData, confirmationResult, managementDeferred, managementFact, managementPage, managementRaw, managementTime, mid, taskReviewData } from './closureTestData'
const ok = (data: unknown, replayed?: string, status = 200, headers = {}) => new Response(JSON.stringify({ code: '0000', message: '成功', data, timestamp: managementTime }), { status, headers: { 'Cache-Control': 'no-store, private', ...(replayed === undefined ? {} : { 'Idempotency-Replayed': replayed }), ...headers } })
const fail = (status: number, code: string, data: unknown = null) => new Response(JSON.stringify({ code, message: '合成管理拒絕', data, timestamp: managementTime }), { status })
const csrf = () => ok({ token: 'synthetic-management-csrf', headerName: 'X-CSRF-TOKEN' })
function queue(...values: (Response | Error)[]) { const fetch = vi.fn<typeof globalThis.fetch>(); for (const value of values) { if (value instanceof Error) fetch.mockRejectedValueOnce(value); else fetch.mockResolvedValueOnce(value) }; vi.stubGlobal('fetch', fetch); return fetch }
const cb = () => ({ expectedVersion: 0, confirmCompletion: true as const, reason: managementRaw })
const ob = () => ({ expectedVersion: 2, confirmClosure: true as const, reason: managementRaw })
beforeEach(() => vi.resetModules())
afterEach(() => vi.unstubAllGlobals())
describe('nine independent manager closure routes', () => {
  it('uses exact routes, allowed filters, same-origin credentials and explicit CSRF', async () => {
    const fetch = queue(ok(managementPage([confirmationData().record])), ok(confirmationData()), ok(managementPage([managementFact()])), ok(managementPage([closureSummary()])), ok(closureData()), ok(managementPage([taskReviewData()])), ok(managementPage([closureWitness()])), csrf(), ok(confirmationResult(), 'false'), ok(closureResult(), 'true'))
    const api = await import('./closureApi'); await api.getCompletionConfirmations({ state: 'ALL', orderId: mid(4).toUpperCase() }); await api.getCompletionConfirmation(mid(1).toUpperCase()); await api.getCompletionConfirmationHistory(mid(1)); await api.getClosureWorklist({ state: 'ALL' }); await api.getOrderClosure(mid(4)); await api.getOrderClosureTasks(mid(4), 2); await api.getOrderClosureHistory(mid(4)); await api.confirmCompletion(mid(1), cb(), 'synthetic-confirm', confirmationData()); await api.closeOrder(mid(4), ob(), 'synthetic-close', closureData())
    expect(fetch.mock.calls.map(([url]) => new URL(String(url), 'https://localhost').pathname)).toEqual(['/api/v1/tms/completions', `/api/v1/tms/completions/${mid(1)}/confirmation`, `/api/v1/tms/completions/${mid(1)}/confirmation/history`, '/api/v1/orders/closure-worklist', `/api/v1/orders/${mid(4)}/closure`, `/api/v1/orders/${mid(4)}/closure/tasks`, `/api/v1/orders/${mid(4)}/closure/history`, '/api/v1/auth/csrf', `/api/v1/tms/completions/${mid(1)}/confirm`, `/api/v1/orders/${mid(4)}/close`])
    expect(String(fetch.mock.calls[0][0])).toContain(`orderId=${mid(4)}`); expect(String(fetch.mock.calls[5][0])).not.toContain('sourceOrderVersion')
    expect(fetch.mock.calls[8][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', redirect: 'error', body: JSON.stringify(cb()), headers: { 'X-CSRF-TOKEN': 'synthetic-management-csrf', 'Idempotency-Key': 'synthetic-confirm' } })
    for (const [path, options] of fetch.mock.calls.filter(([path]) => path !== '/api/v1/auth/csrf')) { expect(String(path)).toMatch(/^\/api\/v1\//); expect(options).toMatchObject({ credentials: 'same-origin', redirect: 'error' }) }
  })
  it('preserves original raw text, recorded policy and independent administrative/execution/source units', async () => { queue(ok(confirmationData(true, 'UNCONFIRMED')), ok(closureData('RECORDED', 'UNCONFIRMED'))); const api = await import('./closureApi'), c = await api.getCompletionConfirmation(mid(1)), o = await api.getOrderClosure(mid(4)); expect(c).toMatchObject({ record: { version: 1, sourceOrderVersion: 2 }, execution: { version: 4 }, policy: { mode: 'UNCONFIRMED', blocker: 'ALREADY_CONFIRMED' } }); expect(c.confirmation!.reason).toBe(managementRaw); expect(o.closure!.reason).toBe(managementRaw); expect(o.policy.mode).toBe('UNCONFIRMED'); expect(o.version).toBe(3); expect(o.closure!.sourceVersion).toBe(2) })
  it('accepts truthful legacy CLOSED without invented time, facts, counts or task proofs', async () => { queue(ok(closureData('LEGACY_CLOSED')), ok(managementPage([]))); const api = await import('./closureApi'); expect(await api.getOrderClosure(mid(4))).toMatchObject({ auditState: 'LEGACY_CLOSED', closure: null, counts: null }); expect((await api.getOrderClosureHistory(mid(4))).content).toEqual([]) })
  it('captures body, baseline source and canonical path before CSRF can finish', async () => { const gate = managementDeferred<Response>(), body = cb(), baseline = confirmationData(), fetch = vi.fn<typeof globalThis.fetch>().mockReturnValueOnce(gate.promise).mockResolvedValueOnce(ok(confirmationResult(), 'false')); vi.stubGlobal('fetch', fetch); const api = await import('./closureApi'), pending = api.confirmCompletion(mid(1).toUpperCase(), body, 'fixed-key', baseline); body.reason = 'changed'; body.expectedVersion = 1; baseline.record.taskId = mid(99); gate.resolve(csrf()); expect((await pending).data.confirmation.reason).toBe(managementRaw); expect(fetch.mock.calls[1][0]).toBe(`/api/v1/tms/completions/${mid(1)}/confirm`); expect(fetch.mock.calls[1][1]?.body).toBe(JSON.stringify(cb())) })
  it.each(['unknownQuery', 'sourceZero', 'missingBaseline', 'wrongBaseline', 'falseConsent', 'blank', 'surrogate', 'overlong'])('refuses %s before any dispatch', async (kind) => { const fetch = queue(), api = await import('./closureApi'); const request = kind === 'unknownQuery' ? api.getClosureWorklist({ q: 'bad' } as never) : kind === 'sourceZero' ? api.getOrderClosureTasks(mid(4), 0) : kind === 'missingBaseline' ? api.confirmCompletion(mid(1), cb(), 'key', undefined as never) : kind === 'wrongBaseline' ? api.confirmCompletion(mid(99), cb(), 'key', confirmationData()) : api.closeOrder(mid(4), { ...ob(), ...(kind === 'falseConsent' ? { confirmClosure: false } : { reason: kind === 'blank' ? '\uFEFF\u001c ' : kind === 'surrogate' ? '\ud800' : '🚚'.repeat(1001) }) } as never, 'key', closureData()); await expect(request).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR', uncertain: false }); expect(fetch).not.toHaveBeenCalled() })
})
describe('strict manager read witnesses', () => {
  it.each(['sourceZero', 'badCounter', 'wrongTaskPolicy', 'missingFact', 'badCompletion', 'newActorId', 'reasonNFCblank'])('rejects confirmation %s', async (kind) => { const d = confirmationData(true); if (kind === 'sourceZero') d.record.sourceOrderVersion = 0; else if (kind === 'badCounter') d.record.version = 0; else if (kind === 'wrongTaskPolicy') d.policy.blocker = null; else if (kind === 'missingFact') d.confirmation = null; else if (kind === 'badCompletion') d.completion.id = mid(99); else if (kind === 'newActorId') Object.assign(d.confirmation!.actor, { id: mid(99) }); else d.confirmation!.reason = ' '; queue(ok(d)); const api = await import('./closureApi'); await expect(api.getCompletionConfirmation(mid(1))).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false }) })
  it.each(['fakeLegacy', 'terminalCounts', 'wrongSource', 'wrongWitness', 'maxReady', 'nonclosedAlready', 'modePromotion'])('rejects closure %s', async (kind) => { const d = closureData(kind === 'fakeLegacy' ? 'LEGACY_CLOSED' : kind === 'terminalCounts' || kind === 'wrongWitness' ? 'RECORDED' : 'OPEN'); if (kind === 'fakeLegacy') d.closure = closureWitness(); else if (kind === 'terminalCounts') d.counts = closureData().counts; else if (kind === 'wrongSource') d.counts!.sourceOrderVersion = 3; else if (kind === 'wrongWitness') d.closure!.sourceVersion = 0; else if (kind === 'maxReady') { d.version = Number.MAX_SAFE_INTEGER; d.counts!.sourceOrderVersion = d.version } else if (kind === 'nonclosedAlready') { d.policy.canClose = false; d.policy.blocker = 'ALREADY_CLOSED'; d.policy.requirements = null } else d.policy.mode = 'UNCONFIRMED'; queue(ok(d)); const api = await import('./closureApi'); await expect(api.getOrderClosure(mid(4))).rejects.toMatchObject({ code: 'INVALID_RESPONSE' }) })
  it.each(['foreignOrder', 'targetInsteadOfSource', 'falseRequired', 'zeroDeparted', 'noAssignment', 'unverifiedId', 'fakeConfirmation'])('rejects task review %s', async (kind) => { const task = taskReviewData(); if (kind === 'foreignOrder') task.orderId = mid(99); else if (kind === 'targetInsteadOfSource') task.sourceOrderVersion = 3; else if (kind === 'falseRequired') task.requiredTask = false as never; else if (kind === 'zeroDeparted') { task.executionVersion = 0; task.executionPhase = 'DEPARTED' } else if (kind === 'noAssignment') task.assignmentId = null; else if (kind === 'unverifiedId') task.completionVerified = false; else task.confirmedAt = null; queue(ok(managementPage([task]))); const api = await import('./closureApi'); await expect(api.getOrderClosureTasks(mid(4), 2)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' }) })
  it('accepts true and unspecified tasks and binds recorded source2 despite current target3', async () => { queue(ok(managementPage([taskReviewData(), taskReviewData(7, null)]))); const api = await import('./closureApi'), result = await api.getOrderClosureTasks(mid(4), 2); expect(result.content.map((v) => v.requiredTask)).toEqual([true, null]); expect(result.content.every((v) => v.sourceOrderVersion === 2)).toBe(true) })
  it.each(['pages', 'contentCount', 'unsafe', 'fraction', 'duplicate', 'utf8'])('rejects page/JSON %s', async (kind) => { const p = managementPage([confirmationData().record]); if (kind === 'pages') p.totalPages = 2; else if (kind === 'contentCount') p.totalElements = 2; else if (kind === 'unsafe') p.totalElements = Number.MAX_SAFE_INTEGER + 1; let raw = JSON.stringify({ code: '0000', message: '成功', data: p, timestamp: managementTime }); if (kind === 'fraction') raw = raw.replace('"version":0', '"version":9007199254740991.1'); else if (kind === 'duplicate') raw = raw.replace('"version":0', '"version":0,"version":0'); queue(kind === 'utf8' ? new Response(new Uint8Array([0xc3, 0x28])) : new Response(raw)); const api = await import('./closureApi'); await expect(api.getCompletionConfirmations()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' }) })
})
describe('write acknowledgements and context-specific E21 errors', () => {
  it.each(['counter', 'source', 'task', 'reason', 'header', 'status', 'actor', 'location'])('keeps confirmation %s acknowledgement unknown', async (kind) => { const v = confirmationResult(); if (kind === 'counter') v.version = 4 as never; else if (kind === 'source') v.sourceOrderVersion = 3; else if (kind === 'task') v.taskId = mid(99); else if (kind === 'reason') v.confirmation.reason = managementRaw.normalize('NFC'); else if (kind === 'actor') Object.assign(v.confirmation.actor, { accountId: mid(99) }); queue(csrf(), ok(v, kind === 'header' ? undefined : 'false', kind === 'status' ? 201 : 200, kind === 'location' ? { Location: '/bad' } : {})); const api = await import('./closureApi'); await expect(api.confirmCompletion(mid(1), cb(), 'key', confirmationData())).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true }) })
  it.each(['counter', 'source', 'order', 'count', 'reason', 'status'])('keeps closure %s acknowledgement unknown', async (kind) => { const v = closureResult(); if (kind === 'counter') v.version = 4; else if (kind === 'source') v.closure.sourceVersion = 3; else if (kind === 'order') v.orderNo = 'OTHER'; else if (kind === 'count') v.closure.taskCount = 1; else if (kind === 'reason') v.closure.reason = managementRaw.normalize('NFC'); else v.lifecycleStatus = 'OPEN' as never; queue(csrf(), ok(v, 'false')); const api = await import('./closureApi'); await expect(api.closeOrder(mid(4), ob(), 'key', closureData())).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true }) })
  it.each(['confirmWrongUnit', 'closeWrongUnit', 'both', 'generic', 'fieldsExtra', 'businessCounter', 'businessFields'])('keeps polluted %s error unknown', async (kind) => { let data: Record<string, unknown> = { reason: 'VERSION_CONFLICT', actualConfirmationVersion: 1 }; if (kind === 'confirmWrongUnit') data = { reason: 'VERSION_CONFLICT', actualOrderVersion: 2 }; else if (kind === 'closeWrongUnit') data = { reason: 'VERSION_CONFLICT', actualConfirmationVersion: 1 }; else if (kind === 'both') data.actualOrderVersion = 2; else if (kind === 'generic') data = { reason: 'VERSION_CONFLICT', actualVersion: 1 }; else if (kind === 'fieldsExtra') data.fields = { reason: 'polluted' }; else data = { reason: 'INVALID_STATE', ...(kind === 'businessCounter' ? { actualOrderVersion: 2 } : { fields: { reason: 'polluted' } }) }; queue(csrf(), fail(409, kind.startsWith('business') ? 'E2102' : 'E2101', data)); const api = await import('./closureApi'); await expect(kind === 'closeWrongUnit' ? api.closeOrder(mid(4), ob(), 'key', closureData()) : api.confirmCompletion(mid(1), cb(), 'key', confirmationData())).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true }) })
  it.each(['confirm', 'close'] as const)('keeps legitimate %s version units explicit', async (kind) => { queue(csrf(), fail(409, 'E2101', { reason: 'VERSION_CONFLICT', [kind === 'confirm' ? 'actualConfirmationVersion' : 'actualOrderVersion']: kind === 'confirm' ? 1 : 3 })); const api = await import('./closureApi'); await expect(kind === 'confirm' ? api.confirmCompletion(mid(1), cb(), 'key', confirmationData()) : api.closeOrder(mid(4), ob(), 'key', closureData())).rejects.toMatchObject({ code: 'E2101', uncertain: false, [kind === 'confirm' ? 'actualConfirmationVersion' : 'actualOrderVersion']: kind === 'confirm' ? 1 : 3 }) })
  it('refreshes CSRF without any automatic resend', async () => { const fetch = queue(csrf(), fail(403, 'E0403', { reason: 'CSRF_INVALID' }), csrf()); const api = await import('./closureApi'); await expect(api.confirmCompletion(mid(1), cb(), 'key', confirmationData())).rejects.toMatchObject({ reason: 'CSRF_INVALID' }); expect(fetch.mock.calls.filter(([, o]) => o?.method === 'POST')).toHaveLength(1) })
  it.each([401, 403])('surfaces refresh auth loss %s', async (status) => { queue(csrf(), fail(403, 'E0403', { reason: 'CSRF_INVALID' }), fail(status, status === 401 ? 'E0401' : 'E0403')); const api = await import('./closureApi'); await expect(api.closeOrder(mid(4), ob(), 'key', closureData())).rejects.toMatchObject({ status, uncertain: true, reason: status === 403 ? 'FORBIDDEN' : null }) })
  it('distinguishes pre-dispatch failure from an unknown dispatched outcome and server5xx', async () => { queue(new Error('synthetic unavailable')); let api = await import('./closureApi'); await expect(api.confirmCompletion(mid(1), cb(), 'key', confirmationData())).rejects.toMatchObject({ uncertain: false }); vi.resetModules(); queue(csrf(), new Error('synthetic lost response')); api = await import('./closureApi'); await expect(api.confirmCompletion(mid(1), cb(), 'key', confirmationData())).rejects.toMatchObject({ uncertain: true }); queue(fail(500, 'E0500')); await expect(api.confirmCompletion(mid(1), cb(), 'key', confirmationData())).rejects.toMatchObject({ code: 'E0500', uncertain: true }) })
})

describe('management transport and immutable original attempts', () => {
  it('retries only after an explicit call with the same frozen body and key after a dispatched response is lost', async () => {
    const fetch = queue(csrf(), new Error('synthetic response lost after send'), ok(closureResult(), 'true'))
    const api = await import('./closureApi')
    await expect(api.closeOrder(mid(4), ob(), 'original-close-key', closureData())).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: true })
    expect(fetch).toHaveBeenCalledTimes(2)
    const retried = await api.closeOrder(mid(4).toUpperCase(), ob(), 'original-close-key', closureData())
    expect(retried.replayed).toBe(true)
    const posts = fetch.mock.calls.filter(([, options]) => options?.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[0][0]).toBe(posts[1][0])
    for (const field of ['method', 'credentials', 'redirect', 'headers', 'body'] as const) expect(posts[0][1]?.[field]).toEqual(posts[1][1]?.[field])
    expect(posts[0][1]?.body).toBe(JSON.stringify(ob()))
  })

  it('freezes close source, count and body before awaiting CSRF, without promoting a historical receipt into current data', async () => {
    const gate = managementDeferred<Response>(), body = ob(), baseline = closureData()
    const fetch = vi.fn<typeof globalThis.fetch>().mockReturnValueOnce(gate.promise).mockResolvedValueOnce(ok(closureResult(), 'true'))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./closureApi'), pending = api.closeOrder(mid(4), body, 'original-key', baseline)
    body.expectedVersion = 3; body.reason = 'new local reason'; baseline.counts!.taskCount = 99; baseline.orderNo = 'MUTATED'
    gate.resolve(csrf())
    const receipt = await pending
    expect(receipt.data).toEqual(closureResult())
    expect(receipt.replayed).toBe(true)
    expect(fetch.mock.calls[1][1]?.body).toBe(JSON.stringify(ob()))
    expect(baseline.version).toBe(2)
  })

  it.each(['0.0', '0e0'])('accepts a mathematical JSON confirmation counter %s and exact raw source text', async (counter) => {
    const detail = confirmationData(); detail.record.taskNo = ' \0e\u0301\r\n任務 '; detail.record.orderNo = ' \0e\u0301 訂單 '
    queue(new Response(JSON.stringify({ code: '0000', message: '成功', data: detail, timestamp: managementTime }).replace('"version":0', `"version":${counter}`)))
    const api = await import('./closureApi'), result = await api.getCompletionConfirmation(mid(1))
    expect(result.record.version).toBe(0)
    expect(result.record.taskNo).toBe(detail.record.taskNo)
    expect(result.record.orderNo).toBe(detail.record.orderNo)
  })

  it.each(['\0', '\u0085', '🚚'.repeat(1000)])('retains a valid nonblank raw reason at Unicode boundaries', async (reason) => {
    const fetch = queue(csrf(), ok(confirmationResult(reason), 'false')), api = await import('./closureApi')
    const result = await api.confirmCompletion(mid(1), { ...cb(), reason }, 'unicode-key', confirmationData())
    expect(result.data.confirmation.reason).toBe(reason)
    expect(JSON.parse(String(fetch.mock.calls[1][1]?.body)).reason).toBe(reason)
  })

  it('keeps a refresh connection failure as the original CSRF refusal and never resends the POST', async () => {
    const fetch = queue(csrf(), fail(403, 'E0403', { reason: 'CSRF_INVALID' }), new Error('synthetic refresh unavailable'))
    const api = await import('./closureApi')
    await expect(api.closeOrder(mid(4), ob(), 'original-key', closureData())).rejects.toMatchObject({ status: 403, code: 'E0403', reason: 'CSRF_INVALID' })
    expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
    expect(fetch.mock.calls).toHaveLength(3)
  })

  it('rejects an already-aborted operation before requesting CSRF or sending a command', async () => {
    const fetch = queue(), controller = new AbortController(); controller.abort()
    const api = await import('./closureApi')
    await expect(api.closeOrder(mid(4), ob(), 'original-key', closureData(), controller.signal)).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: false })
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not dispatch when cancellation arrives during CSRF acquisition', async () => {
    const gate = managementDeferred<Response>(), controller = new AbortController(), fetch = vi.fn<typeof globalThis.fetch>().mockReturnValueOnce(gate.promise)
    vi.stubGlobal('fetch', fetch)
    const api = await import('./closureApi'), pending = api.closeOrder(mid(4), ob(), 'original-key', closureData(), controller.signal)
    controller.abort(); gate.resolve(csrf())
    await expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: false })
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('keeps cancellation after command dispatch uncertain', async () => {
    const sent = managementDeferred<void>(), controller = new AbortController()
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(new DOMException('synthetic abort', 'AbortError')), { once: true }); sent.resolve()
    }))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./closureApi'), pending = api.closeOrder(mid(4), ob(), 'original-key', closureData(), controller.signal)
    await sent.promise; controller.abort()
    await expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: true })
    expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  })

  it('cancels a response stream exceeding the technical cap and does not accept a partial success', async () => {
    const cancel = vi.fn(), stream = new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new Uint8Array(1_048_577)) }, cancel })
    queue(csrf(), new Response(stream))
    const api = await import('./closureApi')
    await expect(api.closeOrder(mid(4), ob(), 'original-key', closureData())).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
    expect(cancel).toHaveBeenCalledTimes(1)
  })
})

describe('wire refusals cannot imitate a trustworthy management result', () => {
  it.each(['E2104', 'E2105', 'E2106'] as const)('retains %s as an uncertain write refusal', async (code) => {
    const api = await import('./closureApi'), fetch = queue(csrf(), fail(409, code, { reason: api.closureBusinessReasons[code] }))
    await expect(api.closeOrder(mid(4), ob(), 'original-key', closureData())).rejects.toMatchObject({ code, uncertain: true })
    expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  })

  it.each(['E2102', 'E2103', 'E2107', 'E2108', 'E2109', 'E2110'] as const)('accepts exact post-ledger-miss %s semantics with no counters or fields', async (code) => {
    const api = await import('./closureApi'); queue(csrf(), fail(409, code, { reason: api.closureBusinessReasons[code] }))
    await expect(api.closeOrder(mid(4), ob(), 'original-key', closureData())).rejects.toMatchObject({ code, reason: api.closureBusinessReasons[code], uncertain: false })
  })

  it.each(['E2111', 'E2001', 'wrongStatus', 'wrongReason', 'nullBusiness', 'emptyBusiness', 'wrongDate', 'wrongOffset'])('keeps malformed %s errors unknown', async (kind) => {
    const value = { code: kind === 'E2111' || kind === 'E2001' ? kind : 'E2102', message: '合成拒絕', data: kind === 'nullBusiness' ? null : kind === 'emptyBusiness' ? {} : { reason: kind === 'wrongReason' ? 'VERSION_CONFLICT' : 'INVALID_STATE' }, timestamp: kind === 'wrongDate' ? '2026-02-30T15:00:00+08:00' : kind === 'wrongOffset' ? '2026-11-26T07:00:00Z' : managementTime }
    queue(csrf(), new Response(JSON.stringify(value), { status: kind === 'wrongStatus' ? 400 : 409 }))
    const api = await import('./closureApi')
    await expect(api.closeOrder(mid(4), ob(), 'original-key', closureData())).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
  })

  it.each(['TRUE', '0', 'yes'])('rejects a nonliteral replay header %s after a successful write', async (header) => {
    queue(csrf(), ok(closureResult(), header)); const api = await import('./closureApi')
    await expect(api.closeOrder(mid(4), ob(), 'original-key', closureData())).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
  })

  it.each(['blankOrderNo', 'recordedZeroVersion', 'recordedOneVersion'])('rejects an impossible worklist %s projection', async (kind) => {
    const row = closureSummary(kind === 'blankOrderNo' ? 'OPEN' : 'RECORDED')
    if (kind === 'blankOrderNo') row.orderNo = ' \t\n '; else row.version = kind === 'recordedZeroVersion' ? 0 : 1
    queue(ok(managementPage([row]))); const api = await import('./closureApi')
    await expect(api.getClosureWorklist()).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false })
  })

  it.each(['legacyDraft', 'cancelledDraft'])('preserves honest %s read data without manufacturing closure audit', async (kind) => {
    const d = closureData(kind === 'legacyDraft' ? 'LEGACY_CLOSED' : 'OPEN', 'UNCONFIRMED')
    d.acceptanceStatus = 'DRAFT'; d.counts = null
    if (kind === 'cancelledDraft') { d.lifecycleStatus = 'CANCELLED'; d.version = 0 }
    queue(ok(d)); const api = await import('./closureApi'), result = await api.getOrderClosure(mid(4))
    expect(result.acceptanceStatus).toBe('DRAFT'); expect(result.closure).toBeNull(); expect(result.counts).toBeNull()
  })
})
