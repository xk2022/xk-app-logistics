import { useEffect, useState } from 'react'
import { ApiError } from './api'
import type { PageData } from './admApi'

export function useAdmPage<T>(load: (page: number, signal?: AbortSignal) => Promise<PageData<T>>, page: number, revision: number, onExpired: () => void) {
  const key = `${page}/${revision}`
  const [state, setState] = useState<{ key: string; loader: typeof load; data?: PageData<T>; error?: unknown }>()
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void load(page, controller.signal).then((data) => { if (active) setState({ key, loader: load, data }) })
      .catch((error: unknown) => { if (active) { setState({ key, loader: load, error }); if (error instanceof ApiError && error.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [load, page, key, onExpired])
  return state?.key === key && state.loader === load ? { loading: false, data: state.data, error: state.error } : { loading: true, data: undefined, error: undefined }
}
