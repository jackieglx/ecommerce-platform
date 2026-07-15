export class ApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly body?: unknown) { super(message) }
}

export async function requestJson<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json', ...init.headers } })
  if (!response.ok) {
    const body = await response.json().catch(() => undefined)
    throw new ApiError(`Request failed with ${response.status}`, response.status, body)
  }
  return response.json() as Promise<T>
}
