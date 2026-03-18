export type ChartInterval = '1m' | '5m' | '15m' | '30m' | '1h' | '4h' | '1d'

export const INTERVAL_OPTIONS: ChartInterval[] = ['1m', '5m', '15m', '30m', '1h', '4h', '1d']

export interface CandleDto {
  epochSec: number
  open: number | null
  high: number | null
  low: number | null
  close: number | null
  volume: number | null
}

export interface CandlestickChartDto {
  symbol: string
  companyName: string | null
  industry: string | null
  logo: string | null
  currentPrice: number | null
  change: number | null
  changePercent: number | null
  dayOpen: number | null
  dayHigh: number | null
  dayLow: number | null
  prevClose: number | null
  dayVolume: number | null
  marketState: string | null
  marketTime: number | null
  delaySec: number | null
  interval: string
  candles: CandleDto[]
}

export interface HeatmapDto {
  symbol: string
  companyName?: string | null
  industry: string | null
  marketCap: number | null
  logo: string | null
  changePercent: number | null
  price: number | null
  change: number | null
  marketState: string | null
  marketTime: number | null
  delaySec: number | null
}

export interface SymbolProfileDto {
  symbol: string
  companyName: string | null
  industry: string | null
  marketCap: number | null
  logo: string | null
}
