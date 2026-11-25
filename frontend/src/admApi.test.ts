import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Definition } from './admApi'

const definition: Definition = { key: 'TEST_VALUE', name: '合成測試參數', description: '隔離測試', valueType: 'TEXT', minLength: 0, maxLength: 20, allowedValues: [], minValue: null, maxValue: null, decimalScale: null }
const category = { id: 'category-id', code: 'TEST_CATEGORY', name: '合成分類', enabled: true, sortOrder: 0, readOnly: false, version: 1 }
const item = { id: 'item-id', categoryId: 'category-id', code: 'TEST_ITEM', label: '合成項目', enabled: false, sortOrder: 2, readOnly: false, version: 3 }
function response(data: unknown, status = 200) { return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0403', message: '成功', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status }) }
beforeEach(() => { vi.resetModules() })

describe('ADM transport and types', () => {
  it('encodes identifiers and applies bounded page requests', async () => {
    const fetch = vi.fn().mockResolvedValue(response({ content: [item], page: 1, size: 20, totalElements: 21, totalPages: 2 }))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./admApi')
    const page = await api.getItems('category/with slash', 1)
    expect(page.content[0].code).toBe('TEST_ITEM')
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/adm/categories/category%2Fwith%20slash/items?page=1&size=20')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'GET', credentials: 'same-origin' })
  })

  it('rejects missing readOnly flags and malformed page responses', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ content: [{ ...category, readOnly: undefined }], page: 0, size: 20, totalElements: 1, totalPages: 1 }))
      .mockResolvedValueOnce(response({ content: [], page: 0, size: 0, totalElements: 0, totalPages: 0 }))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./admApi')
    await expect(api.getCategories(0)).rejects.toThrow('管理資料回應格式不正確')
    await expect(api.getParameters(0)).rejects.toThrow('管理資料回應格式不正確')
  })

  it('sends only mutable category and item fields with optimistic versions and CSRF', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(category)).mockResolvedValueOnce(response(item))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./admApi')
    await api.updateCategory(category.id, { name: '更新分類', enabled: false, sortOrder: 2, version: 1 })
    await api.updateItem(item.id, { label: '更新項目', enabled: true, sortOrder: 3, version: 3 })
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ name: '更新分類', enabled: false, sortOrder: 2, version: 1 })
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ label: '更新項目', enabled: true, sortOrder: 3, version: 3 })
    expect(fetch.mock.calls[2][1]).toMatchObject({ method: 'PATCH', headers: { 'X-CSRF-TOKEN': 'test-token' } })
  })

  it('keeps parameter values as strings and never patches key or type', async () => {
    const parameter = { id: 'parameter-id', key: 'TEST_FLAG', name: '合成旗標', valueType: 'BOOLEAN', valueText: 'false', description: '', enabled: true, version: 0 }
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(parameter)).mockResolvedValueOnce(response({ ...parameter, valueText: 'true', version: 1 }))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./admApi')
    await api.createParameter({ key: 'TEST_FLAG', name: '合成旗標', valueType: 'BOOLEAN', valueText: 'false', description: '', enabled: true })
    await api.updateParameter(parameter.id, { name: '合成旗標', valueText: 'true', description: '', enabled: false, version: 0 })
    expect(JSON.parse(fetch.mock.calls[1][1].body).valueText).toBe('false')
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ name: '合成旗標', valueText: 'true', description: '', enabled: false, version: 0 })
  })

  it('rejects an unknown parameter type and invalid log time before rendering', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response([{ ...definition, valueType: 'SECRET' }]))
      .mockResolvedValueOnce(response({ content: [{ id: 'id', targetType: 'CATEGORY', targetId: 'target', action: 'CREATE', actorId: 'actor', actorUsername: 'test.admin', occurredAt: 'bad', before: null, after: {} }], page: 0, size: 20, totalElements: 1, totalPages: 1 })))
    const api = await import('./admApi')
    await expect(api.getDefinitions()).rejects.toThrow('管理資料回應格式不正確')
    await expect(api.getChangeLogs(0)).rejects.toThrow('管理資料回應格式不正確')
  })
})

describe('approved parameter value validation', () => {
  it('validates normalized text length and approved values', async () => {
    const { validateParameterValue } = await import('./admApi')
    expect(validateParameterValue('e\u0301', { ...definition, minLength: 1, maxLength: 1, allowedValues: ['é'] })).toBeNull()
    expect(validateParameterValue('unapproved', { ...definition, allowedValues: ['approved'] })).toContain('核准')
    expect(validateParameterValue('', { ...definition, minLength: 1 })).toContain('至少')
    expect(validateParameterValue('too long', { ...definition, maxLength: 2 })).toContain('最多')
  })

  it('validates exact decimal bounds without floating-point rounding', async () => {
    const { validateParameterValue } = await import('./admApi')
    const numeric = { ...definition, valueType: 'NUMBER' as const, minValue: '9007199254740993.01', maxValue: '9007199254740993.03', decimalScale: 2 }
    expect(validateParameterValue('9007199254740993.02', numeric)).toBeNull()
    expect(validateParameterValue('9007199254740993.00', numeric)).toContain('不得小於')
    expect(validateParameterValue('9007199254740993.04', numeric)).toContain('不得大於')
    expect(validateParameterValue('9007199254740993.021', numeric)).toContain('小數')
    expect(validateParameterValue('-1.10', { ...numeric, minValue: '-2', maxValue: '0' })).toBeNull()
  })

  it.each(['', '1e3', 'NaN', 'Infinity', ' 1', '01', '1.'])('rejects noncanonical decimal %j', async (value) => {
    const { validateParameterValue } = await import('./admApi')
    expect(validateParameterValue(value, { ...definition, valueType: 'NUMBER' })).not.toBeNull()
  })

  it('preserves false and zero as valid values and rejects loose booleans', async () => {
    const { validateParameterValue } = await import('./admApi')
    expect(validateParameterValue('false', { ...definition, valueType: 'BOOLEAN' })).toBeNull()
    expect(validateParameterValue('0', { ...definition, valueType: 'NUMBER' })).toBeNull()
    expect(validateParameterValue('False', { ...definition, valueType: 'BOOLEAN' })).not.toBeNull()
  })
})
