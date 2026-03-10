import type { CandlestickChartDto, HeatmapDto } from '../types/stock'

const API_PREFIX = '/api'

async function requestJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  if (!response.ok) {
    throw new Error(`Request failed (${response.status})`)
  }

  return (await response.json()) as T
}

export async function getHeatmap(signal?: AbortSignal): Promise<HeatmapDto[]> {
  return requestJson<HeatmapDto[]>(`${API_PREFIX}/heatmap`, signal)
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
