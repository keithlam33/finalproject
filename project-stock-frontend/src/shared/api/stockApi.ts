import type { CandlestickChartDto, HeatmapDto, SymbolProfileDto } from '../types/stock'

const API_PREFIX = '/api'

interface ApiErrorPayload {
  code?: number
  message?: string
}

class ApiError extends Error {
  status: number
  code?: number

  constructor(status: number, message: string, code?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function requestJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  if (!response.ok) {
    const contentType = response.headers.get('content-type') ?? ''
    let message = `Request failed (${response.status})`
    let code: number | undefined

    if (contentType.includes('application/json')) {
      const payload = (await response.json()) as ApiErrorPayload
      if (typeof payload.message === 'string' && payload.message.trim().length > 0) {
        message = payload.message
      }
      if (typeof payload.code === 'number') {
        code = payload.code
      }
    } else {
      const bodyText = await response.text()
      if (bodyText.trim().length > 0) {
        message = bodyText
      }
    }

    throw new ApiError(response.status, message, code)
  }

  return (await response.json()) as T
}

export async function getHeatmap(signal?: AbortSignal): Promise<HeatmapDto[]> {
  return requestJson<HeatmapDto[]>(`${API_PREFIX}/heatmap`, signal)
}

export async function getSymbols(signal?: AbortSignal): Promise<SymbolProfileDto[]> {
  return requestJson<SymbolProfileDto[]>(`${API_PREFIX}/symbols`, signal)
}

export interface CandlestickQuery {
  symbol: string
  interval: string
  limit?: number
  beforeTs?: number
  signal?: AbortSignal
}

export async function getCandlestick(query: CandlestickQuery): Promise<CandlestickChartDto> {
  const params = new URLSearchParams({
    symbol: query.symbol,
    interval: query.interval,
  })

  if (typeof query.limit === 'number') {
    params.set('limit', String(query.limit))
  }
  if (typeof query.beforeTs === 'number') {
    params.set('beforeTs', String(query.beforeTs))
  }

  return requestJson<CandlestickChartDto>(`${API_PREFIX}/candlestick?${params.toString()}`, query.signal)
}
