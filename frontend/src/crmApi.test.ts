import { beforeEach, describe, expect, it, vi } from 'vitest'

const customer = { id: 'customer-id', customerCode: 'TEST-CUSTOMER', name: '合成客戶', taxId: null, phone: null, email: null, enabled: true, version: 1 }
const contact = { id: 'contact-id', customerId: 'customer-id', name: '合成聯絡人', title: null, phone: null, email: null, enabled: true, version: 2 }
const address = { id: 'address-id', customerId: 'customer-id', label: null, address: '合成地址一號\n二樓', addressType: null, enabled: true, version: 3 }
const paged = (content: unknown[], page = 0, size = 20, totalElements = content.length) => ({ content, page, size, totalElements, totalPages: Math.ceil(totalElements / size) })
function response(data: unknown, status = 200) { return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0400', message: '成功', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status }) }
beforeEach(() => { vi.resetModules() })

describe('CRM API transport', () => {
  it('encodes literal search, includes enabled=false, and respects selected page size', async () => {
    const fetch = vi.fn().mockResolvedValue(response(paged([customer], 1, 5, 6)))
    vi.stubGlobal('fetch', fetch)
    const { getCustomers } = await import('./crmApi')
    const result = await getCustomers(1, { q: '合成 & + _ %', enabled: 'false', size: 5 })
    const url = new URL(fetch.mock.calls[0][0], 'https://localhost')
    expect(url.searchParams.get('q')).toBe('合成 & + _ %')
    expect(url.searchParams.get('enabled')).toBe('false')
    expect(url.searchParams.get('size')).toBe('5')
    expect(result.totalPages).toBe(2)
  })

  it('queries active options without an enabled parameter or write', async () => {
    const fetch = vi.fn().mockResolvedValue(response(paged([{ id: customer.id, customerCode: customer.customerCode, name: customer.name, version: 1 }])))
    vi.stubGlobal('fetch', fetch)
    const { getCustomerOptions } = await import('./crmApi')
    expect((await getCustomerOptions(0, 'TEST')).content[0].version).toBe(1)
    const url = new URL(fetch.mock.calls[0][0], 'https://localhost')
    expect(url.pathname).toBe('/api/v1/crm/customer-options')
    expect(url.searchParams.has('enabled')).toBe(false)
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'GET', credentials: 'same-origin' })
  })

  it('supports minimal creation and partial updates with explicit nullable clearing and CSRF', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(customer, 201)).mockResolvedValueOnce(response({ ...customer, taxId: null, version: 2 }))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./crmApi')
    await api.createCustomer({ customerCode: 'TEST-CUSTOMER', name: '合成客戶' })
    await api.updateCustomer(customer.id, { taxId: null, version: 1 })
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ customerCode: 'TEST-CUSTOMER', name: '合成客戶' })
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ taxId: null, version: 1 })
    expect(fetch.mock.calls[2][1]).toMatchObject({ method: 'PATCH', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf' } })
  })

  it('uses immutable parent paths for contact and address updates', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(contact)).mockResolvedValueOnce(response(address))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./crmApi')
    await api.updateContact('parent/id', 'contact/id', { name: '合成聯絡人', enabled: false, version: 2 })
    await api.updateAddress('parent/id', 'address/id', { addressType: '自由描述', version: 3 })
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/crm/customers/parent%2Fid/contacts/contact%2Fid')
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ name: '合成聯絡人', enabled: false, version: 2 })
    expect(fetch.mock.calls[2][0]).toBe('/api/v1/crm/customers/parent%2Fid/addresses/address%2Fid')
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ addressType: '自由描述', version: 3 })
  })

  it('validates nullable response types and page metadata before use', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ ...customer, taxId: 12345678 }))
      .mockResolvedValueOnce(response({ ...paged([contact]), page: -1 }))
      .mockResolvedValueOnce(response(paged([{ id: 'id', customerCode: 'TEST', name: '合成', version: undefined }])))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./crmApi')
    await expect(api.getCustomer(customer.id)).rejects.toThrow('客戶資料回應格式不正確')
    await expect(api.getContacts(customer.id, 0)).rejects.toThrow('客戶資料回應格式不正確')
    await expect(api.getCustomerOptions(0, '')).rejects.toThrow('客戶資料回應格式不正確')
  })
})

describe('CRM text validation', () => {
  it('counts normalized Unicode codepoints and preserves international telephone text', async () => {
    const { validateText, nullable } = await import('./crmApi')
    expect(() => validateText('客'.repeat(200), '客戶名稱', 200, true)).not.toThrow()
    expect(() => validateText('客'.repeat(201), '客戶名稱', 200, true)).toThrow('200')
    expect(() => validateText('e\u0301'.repeat(200), '客戶名稱', 200, true)).not.toThrow()
    expect(() => validateText('+44 (0)20 0000 0000 ext. 123', '電話', 50)).not.toThrow()
    expect(nullable('  +44 (0)20 0000 0000 ext. 123 ')).toBe('+44 (0)20 0000 0000 ext. 123')
    expect(nullable('  ')).toBeNull()
    expect(nullable('NON-UNIQUE-TAX-ID')).toBe('NON-UNIQUE-TAX-ID')
  })

  it('accepts optional email or validates basic syntax without sending mail', async () => {
    const { validateEmail } = await import('./crmApi')
    expect(() => validateEmail('')).not.toThrow()
    expect(() => validateEmail('synthetic@example.invalid')).not.toThrow()
    expect(() => validateEmail('invalid-address')).toThrow('基本格式')
    expect(() => validateEmail(`${'a'.repeat(250)}@example.invalid`)).toThrow('254')
  })
})
