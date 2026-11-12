/** Keep API requests and map fetches on the same backend when the frontend is hosted separately. */
export function normalizeApiBaseUrl(value?: string) {
  return value?.trim().replace(/\/+$/, '') || '/api'
}

export function resolveApiEndpoint(baseUrl: string, path?: string) {
  if (!path) return ''
  const relativePath = path.startsWith('/api/') ? path.slice(4) : path
  return `${normalizeApiBaseUrl(baseUrl)}${relativePath.startsWith('/') ? '' : '/'}${relativePath}`
}
