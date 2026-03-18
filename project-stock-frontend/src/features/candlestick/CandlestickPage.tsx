import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import { getCandlestick, getSymbols } from '../../shared/api/stockApi'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import type { CandleDto, CandlestickChartDto, ChartInterval, SymbolProfileDto } from '../../shared/types/stock'
import { INTERVAL_OPTIONS } from '../../shared/types/stock'

const DEFAULT_WINDOW = 240
const MIN_WINDOW = 40
const CANDLE_POLL_MS = 10_000
const SYMBOL_DEBOUNCE_MS = 300
const MAX_SUGGESTIONS = 10

interface ZoomRange {
  start: number
  end: number
}

interface DataZoomEvent {
  start?: number
  end?: number
  batch?: Array<{ start?: number; end?: number }>
}

interface TooltipAxisParam {
  axisValueLabel?: string
  seriesName?: string
  dataIndex?: number
  value?: number | number[]
  data?: number | number[]
}

function isValidNumber(value: number | null): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function normalizeCandles(input: CandleDto[]): CandleDto[] {
  return [...input]
    .filter((candle) => Number.isFinite(candle.epochSec))
    .sort((a, b) => a.epochSec - b.epochSec)
}

function mergeCandles(base: CandleDto[], incoming: CandleDto[]): CandleDto[] {
  const merged = new Map<number, CandleDto>()
  for (const candle of base) {
    merged.set(candle.epochSec, candle)
  }
  for (const candle of incoming) {
    merged.set(candle.epochSec, candle)
  }
  return [...merged.values()].sort((a, b) => a.epochSec - b.epochSec)
}

function mergeLatest(base: CandleDto[], latest: CandleDto): CandleDto[] {
  if (base.length === 0) {
    return [latest]
  }

  const last = base[base.length - 1]
  if (latest.epochSec === last.epochSec) {
    const next = [...base]
    next[next.length - 1] = latest
    return next
  }
  if (latest.epochSec > last.epochSec) {
    return [...base, latest]
  }
  return base
}

function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Unknown error'
}

function formatPrice(value: number | null): string {
  if (value == null) return '--'
  return value.toFixed(2)
}

function formatPercent(value: number | null): string {
  if (value == null) return '--'
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function formatVolume(value: number | null): string {
  if (value == null || !Number.isFinite(value)) return '--'
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)}B`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(2)}K`
  return `${Math.round(value)}`
}

function formatVolumeAxis(value: number): string {
  if (!Number.isFinite(value)) return '--'
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(1)}B`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`
  return `${Math.round(value)}`
}

function formatMarketCap(value: number | null): string {
  if (value == null || !Number.isFinite(value)) return '--'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}T`
  if (value >= 1_000) return `${(value / 1_000).toFixed(2)}B`
  return `${value.toFixed(2)}M`
}

function toTimeLabel(epochSec: number): string {
  return new Date(epochSec * 1000).toLocaleString()
}

function formatTooltip(params: TooltipAxisParam | TooltipAxisParam[], volumeSeries: number[]): string {
  const items = Array.isArray(params) ? params : [params]
  const lines: string[] = []

  const axisLabel = items[0]?.axisValueLabel
  if (axisLabel) {
    lines.push(axisLabel)
  }

  const priceItem = items.find((item) => item.seriesName === 'Price')
  const priceValue = Array.isArray(priceItem?.data)
    ? priceItem.data
    : Array.isArray(priceItem?.value)
      ? priceItem.value
      : null

  if (priceValue && priceValue.length >= 4) {
    const [open, close, low, high] = priceValue
    lines.push(`Open: ${formatPrice(open)}`)
    lines.push(`High: ${formatPrice(high)}`)
    lines.push(`Low: ${formatPrice(low)}`)
    lines.push(`Close: ${formatPrice(close)}`)
  }

  const volumeItem = items.find((item) => item.seriesName === 'Volume')
  const volumeValueFromSeries = typeof volumeItem?.data === 'number'
    ? volumeItem.data
    : typeof volumeItem?.value === 'number'
      ? volumeItem.value
      : null

  const priceIndex = items.find((item) => typeof item.dataIndex === 'number')?.dataIndex
  const volumeValueFromIndex = typeof priceIndex === 'number'
    ? volumeSeries[priceIndex] ?? null
    : null

  const volumeValue = volumeValueFromSeries ?? volumeValueFromIndex

  if (volumeValue != null) {
    lines.push(`Volume: ${formatVolume(volumeValue)}`)
  }

  return lines.join('<br/>')
}

interface CandlestickPageProps {
  selectedSymbol?: string | null
}

export function CandlestickPage({ selectedSymbol }: CandlestickPageProps) {
  const [symbolInput, setSymbolInput] = useState<string>(() => {
    const symbol = selectedSymbol?.trim().toUpperCase()
    return symbol && symbol.length > 0 ? symbol : 'TSLA'
  })
  const [symbol, setSymbol] = useState<string>(() => {
    const initial = selectedSymbol?.trim().toUpperCase()
    return initial && initial.length > 0 ? initial : 'TSLA'
  })
  const [interval, setInterval] = useState<ChartInterval>('1m')
  const [windowSize, setWindowSize] = useState<number>(DEFAULT_WINDOW)
  const [candles, setCandles] = useState<CandleDto[]>([])
  const [snapshot, setSnapshot] = useState<CandlestickChartDto | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [loadingOlder, setLoadingOlder] = useState<boolean>(false)
  const [hasMoreOlder, setHasMoreOlder] = useState<boolean>(true)
  const [error, setError] = useState<string | null>(null)
  const [zoomRange, setZoomRange] = useState<ZoomRange>({ start: 0, end: 100 })
  const [symbolOptions, setSymbolOptions] = useState<SymbolProfileDto[]>([])
  const [symbolsReady, setSymbolsReady] = useState<boolean>(false)
  const [symbolsError, setSymbolsError] = useState<string | null>(null)
  const [symbolFocused, setSymbolFocused] = useState<boolean>(false)
  const [highlightIndex, setHighlightIndex] = useState<number>(-1)

  const chartRef = useRef<ReactECharts>(null)
  const windowSizeRef = useRef<number>(windowSize)
  const loadingOlderRef = useRef<boolean>(false)
  const olderAbortRef = useRef<AbortController | null>(null)

  const debouncedInput = useDebouncedValue(symbolInput.trim().toUpperCase(), SYMBOL_DEBOUNCE_MS)
  const isAtRightEdge = zoomRange.end >= 98

  useEffect(() => {
    const symbol = selectedSymbol?.trim().toUpperCase()
    if (!symbol) return
    setSymbolInput(symbol)
    setSymbol(symbol)
    setError(null)
    setHighlightIndex(-1)
  }, [selectedSymbol])

  useEffect(() => {
    let disposed = false
    const controller = new AbortController()

    void getSymbols(controller.signal)
      .then((data) => {
        if (disposed) return
        setSymbolOptions(data)
        setSymbolsReady(true)
        setSymbolsError(null)
      })
      .catch((err) => {
        if (controller.signal.aborted || disposed) return
        setSymbolsError(toErrorMessage(err))
        setSymbolsReady(false)
      })

    return () => {
      disposed = true
      controller.abort()
    }
  }, [])

  const symbolMap = useMemo(() => {
    const map = new Map<string, SymbolProfileDto>()
    for (const opt of symbolOptions) {
      const key = opt.symbol?.trim().toUpperCase()
      if (!key) continue
      map.set(key, opt)
    }
    return map
  }, [symbolOptions])

  const suggestions = useMemo(() => {
    const query = symbolInput.trim().toUpperCase()
    if (!query) return []

    const out: SymbolProfileDto[] = []
    for (const opt of symbolOptions) {
      const sym = opt.symbol?.trim().toUpperCase()
      if (!sym) continue
      if (sym.startsWith(query)) {
        out.push(opt)
      }
      if (out.length >= MAX_SUGGESTIONS) break
    }

    if (out.length >= MAX_SUGGESTIONS) return out

    for (const opt of symbolOptions) {
      const sym = opt.symbol?.trim().toUpperCase()
      if (!sym) continue
      if (out.some((existing) => existing.symbol === sym)) continue
      const name = opt.companyName?.toUpperCase() ?? ''
      if (name.includes(query)) {
        out.push(opt)
      }
      if (out.length >= MAX_SUGGESTIONS) break
    }

    return out
  }, [symbolInput, symbolOptions])

  const selectSymbol = useCallback(
    (nextSymbol: string) => {
      const normalized = nextSymbol.trim().toUpperCase()
      if (!normalized) return
      setSymbolInput(normalized)
      setSymbol(normalized)
      setError(null)
      setHighlightIndex(-1)
    },
    [],
  )

  useEffect(() => {
    if (!debouncedInput) return
    if (symbolsReady && symbolMap.size > 0 && symbolMap.has(debouncedInput) && debouncedInput !== symbol) {
      selectSymbol(debouncedInput)
    }
  }, [debouncedInput, selectSymbol, symbol, symbolMap, symbolsReady])

  const inputProfile = useMemo(() => {
    const key = symbol.trim().toUpperCase()
    return symbolMap.get(key) ?? null
  }, [symbol, symbolMap])

  const displayProfile = snapshot ?? inputProfile
  const displayLogo = snapshot?.logo ?? inputProfile?.logo ?? null
  const displayCompany = snapshot?.companyName ?? inputProfile?.companyName ?? symbol

  useEffect(() => {
    windowSizeRef.current = windowSize
  }, [windowSize])

  useEffect(() => {
    return () => {
      olderAbortRef.current?.abort()
    }
  }, [])

  const loadOlder = useCallback(async () => {
    if (!symbol || !hasMoreOlder || loadingOlderRef.current) {
      return
    }

    const oldestTs = candles[0]?.epochSec
    if (oldestTs == null) {
      return
    }

    const chunkSize = Math.max(MIN_WINDOW, windowSizeRef.current)
    loadingOlderRef.current = true
    setLoadingOlder(true)

    olderAbortRef.current?.abort()
    const controller = new AbortController()
    olderAbortRef.current = controller

    try {
      const dto = await getCandlestick({
        symbol,
        interval,
        limit: chunkSize,
        beforeTs: oldestTs,
        signal: controller.signal,
      })

      const olderCandles = normalizeCandles(dto.candles ?? [])
      if (olderCandles.length === 0) {
        setHasMoreOlder(false)
        return
      }

      setCandles((prev) => mergeCandles(prev, olderCandles))
      if (olderCandles.length < chunkSize) {
        setHasMoreOlder(false)
      }
    } catch (err) {
      if (!controller.signal.aborted) {
        setError(toErrorMessage(err))
      }
    } finally {
      loadingOlderRef.current = false
      setLoadingOlder(false)
    }
  }, [candles, hasMoreOlder, interval, symbol])

  useEffect(() => {
    if (!symbol) {
      setCandles([])
      setLoading(false)
      return
    }

    if (symbolsReady && symbolMap.size > 0 && !symbolMap.has(symbol)) {
      setSnapshot(null)
      setCandles([])
      setLoading(false)
      setError(`Symbol not found: ${symbol}`)
      return
    }

    let disposed = false
    const controller = new AbortController()

    setLoading(true)
    setError(null)
    setHasMoreOlder(true)

    const requestWindow = Math.max(MIN_WINDOW, windowSizeRef.current)

    void getCandlestick({
      symbol,
      interval,
      limit: requestWindow,
      signal: controller.signal,
    })
      .then((dto) => {
        if (disposed) return
        const normalized = normalizeCandles(dto.candles ?? [])
        setSnapshot(dto)
        setCandles(normalized)
        setHasMoreOlder(normalized.length >= requestWindow)

        const start = normalized.length > requestWindow ? Math.max(0, 100 - (requestWindow / normalized.length) * 100) : 0
        setZoomRange({ start, end: 100 })
      })
      .catch((err) => {
        if (controller.signal.aborted || disposed) return
        setError(toErrorMessage(err))
        setCandles([])
      })
      .finally(() => {
        if (!disposed) {
          setLoading(false)
        }
      })

    return () => {
      disposed = true
      controller.abort()
    }
  }, [interval, symbol, symbolMap, symbolsReady])

  useEffect(() => {
    if (!symbol) return

    let disposed = false
    let controller: AbortController | null = null

    const pollLatest = async () => {
      controller?.abort()
      controller = new AbortController()

      try {
        const dto = await getCandlestick({
          symbol,
          interval,
          limit: 1,
          signal: controller.signal,
        })
        if (disposed) return

        setSnapshot(dto)
        const latestCandles = normalizeCandles(dto.candles ?? [])
        const latest = latestCandles[latestCandles.length - 1]
        if (!latest) return

        setCandles((prev) => {
          const next = mergeLatest(prev, latest)
          if (isAtRightEdge) {
            const start = next.length > windowSizeRef.current
              ? Math.max(0, 100 - (windowSizeRef.current / next.length) * 100)
              : 0
            setZoomRange({ start, end: 100 })
          }
          return next
        })
      } catch (err) {
        if (!controller.signal.aborted && !disposed) {
          setError(toErrorMessage(err))
        }
      }
    }

    void pollLatest()
    const timerId = window.setInterval(() => {
      void pollLatest()
    }, CANDLE_POLL_MS)

    return () => {
      disposed = true
      controller?.abort()
      window.clearInterval(timerId)
    }
  }, [interval, isAtRightEdge, symbol])

  const handleDataZoom = useCallback(
    (event: unknown) => {
      if (candles.length === 0) return

      const payload = event as DataZoomEvent
      const start = payload.batch?.[0]?.start ?? payload.start
      const end = payload.batch?.[0]?.end ?? payload.end
      if (start == null || end == null) return

      setZoomRange({ start, end })

      const percentage = Math.max(1, end - start)
      const estimatedVisible = Math.max(MIN_WINDOW, Math.round((percentage / 100) * candles.length))
      setWindowSize((prev) => (Math.abs(prev - estimatedVisible) >= 4 ? estimatedVisible : prev))

      if (start <= 5) {
        void loadOlder()
      }
    },
    [candles.length, loadOlder],
  )

  const onEvents = useMemo(() => ({ datazoom: handleDataZoom }), [handleDataZoom])

  const seriesData = useMemo(() => {
    const labels: string[] = []
    const ohlc: number[][] = []
    const volume: number[] = []

    for (const candle of candles) {
      if (
        !isValidNumber(candle.open)
        || !isValidNumber(candle.high)
        || !isValidNumber(candle.low)
        || !isValidNumber(candle.close)
      ) {
        continue
      }

      labels.push(toTimeLabel(candle.epochSec))
      ohlc.push([candle.open, candle.close, candle.low, candle.high])
      volume.push(candle.volume ?? 0)
    }

    return { labels, ohlc, volume }
  }, [candles])

  const chartOption = useMemo<EChartsOption>(
    () => ({
      animation: false,
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        formatter: (params) => formatTooltip(params as TooltipAxisParam | TooltipAxisParam[], seriesData.volume),
      },
      grid: [
        { left: 48, right: 20, top: 20, height: '62%' },
        { left: 48, right: 20, top: '74%', height: '16%' },
      ],
      xAxis: [
        {
          type: 'category',
          data: seriesData.labels,
          boundaryGap: false,
          axisLine: { lineStyle: { color: '#526070' } },
          axisLabel: { color: '#6b7280' },
          splitLine: { show: false },
        },
        {
          type: 'category',
          gridIndex: 1,
          data: seriesData.labels,
          boundaryGap: false,
          axisLine: { lineStyle: { color: '#526070' } },
          axisLabel: { show: false },
          splitLine: { show: false },
        },
      ],
      yAxis: [
        {
          scale: true,
          splitArea: { show: false },
          splitLine: { lineStyle: { color: '#d7dde4' } },
          axisLabel: {
            color: '#6b7280',
            formatter: (value: number) => formatPrice(value),
          },
        },
        {
          gridIndex: 1,
          scale: true,
          splitLine: { show: false },
          axisLabel: {
            color: '#6b7280',
            formatter: (value: number) => formatVolumeAxis(value),
          },
        },
      ],
      dataZoom: [
        {
          type: 'inside',
          xAxisIndex: [0, 1],
          start: zoomRange.start,
          end: zoomRange.end,
          zoomLock: false,
          moveOnMouseMove: true,
          moveOnMouseWheel: false,
          zoomOnMouseWheel: true,
          preventDefaultMouseMove: true,
        },
        {
          type: 'slider',
          xAxisIndex: [0, 1],
          start: zoomRange.start,
          end: zoomRange.end,
          bottom: 8,
          height: 28,
        },
      ],
      series: [
        {
          name: 'Price',
          type: 'candlestick',
          data: seriesData.ohlc,
          itemStyle: {
            color: '#15803d',
            color0: '#b91c1c',
            borderColor: '#15803d',
            borderColor0: '#b91c1c',
          },
        },
        {
          name: 'Volume',
          type: 'bar',
          xAxisIndex: 1,
          yAxisIndex: 1,
          data: seriesData.volume,
          itemStyle: {
            color: '#8fb2d5',
          },
        },
      ],
    }),
    [seriesData, zoomRange.end, zoomRange.start],
  )

  const handleSymbolKeyDown = useCallback(
    (event: KeyboardEvent<HTMLInputElement>) => {
      if (event.key === 'Escape') {
        setSymbolFocused(false)
        setHighlightIndex(-1)
        return
      }

      if (event.key === 'ArrowDown') {
        event.preventDefault()
        setSymbolFocused(true)
        setHighlightIndex((prev) => Math.min(prev + 1, suggestions.length - 1))
        return
      }

      if (event.key === 'ArrowUp') {
        event.preventDefault()
        setHighlightIndex((prev) => Math.max(prev - 1, 0))
        return
      }

      if (event.key === 'Enter') {
        event.preventDefault()

        if (symbolFocused && highlightIndex >= 0 && highlightIndex < suggestions.length) {
          const pick = suggestions[highlightIndex]
          selectSymbol(pick.symbol)
          setSymbolFocused(false)
          return
        }

        const candidate = symbolInput.trim().toUpperCase()
        if (!candidate) return
        if (symbolsReady && symbolMap.size > 0 && !symbolMap.has(candidate)) {
          setError(`Symbol not found: ${candidate}`)
          return
        }
        selectSymbol(candidate)
        setSymbolFocused(false)
      }
    },
    [highlightIndex, selectSymbol, suggestions, symbolFocused, symbolInput, symbolMap, symbolsReady],
  )

  return (
    <section className="panel">
      <header className="panel-head">
        <div>
          <h2>Candlestick</h2>
          <p>
            Latest candle updates every 10 seconds.
            {interval === '15m' ? ' 15m candles only update when the candle closes (may look delayed).' : ''}
          </p>
        </div>
      </header>

      <div className="panel-meta candle-meta-bar">
        <span>{snapshot?.marketTime ? `Updated ${new Date(snapshot.marketTime * 1000).toLocaleTimeString()}` : 'Waiting for first load'}</span>
        <span>Visible window: {windowSize}</span>
        <span>{isAtRightEdge ? 'Following latest' : 'Historical view'}</span>
        <span>Drag chart to pan history, use wheel to zoom</span>
      </div>

      <div className="candle-toolbar">
        <div className="candle-toolbar-main">
          <label className="symbol-input-wrap candle-symbol-wrap">
            <span className="sr-only">Symbol</span>
            <div className={symbolFocused ? 'symbol-combobox active' : 'symbol-combobox'}>
              {displayLogo ? (
                <img className="symbol-combobox-logo" src={displayLogo} alt={`${symbol} logo`} />
              ) : (
                <div className="symbol-combobox-logo symbol-combobox-logo-fallback">{symbol.slice(0, 2)}</div>
              )}
              <input
                className="symbol-combobox-input"
                value={symbolInput}
                onChange={(event) => {
                  setSymbolInput(event.target.value.toUpperCase())
                  setHighlightIndex(-1)
                  setSymbolFocused(true)
                }}
                onFocus={() => setSymbolFocused(true)}
                onBlur={() => setSymbolFocused(false)}
                onKeyDown={handleSymbolKeyDown}
                placeholder="TSLA"
                maxLength={24}
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            {symbolFocused && suggestions.length > 0 && (
              <div className="symbol-suggest" role="listbox" aria-label="Symbol suggestions">
                {suggestions.map((opt, idx) => {
                  const active = idx === highlightIndex
                  return (
                    <button
                      key={opt.symbol}
                      type="button"
                      className={active ? 'symbol-suggest-item active' : 'symbol-suggest-item'}
                      role="option"
                      aria-selected={active}
                      onMouseDown={(e) => {
                        e.preventDefault()
                        selectSymbol(opt.symbol)
                        setSymbolFocused(false)
                      }}
                      onMouseEnter={() => setHighlightIndex(idx)}
                    >
                      <span className="symbol-suggest-sym">{opt.symbol}</span>
                      <span className="symbol-suggest-name">{opt.companyName ?? ''}</span>
                    </button>
                  )
                })}
              </div>
            )}
          </label>

          <div className="candle-inline-stats">
            <div className="candle-stat">
              <span>Name</span>
              <strong>{displayCompany ?? symbol}</strong>
            </div>
            <div className="candle-stat">
              <span>Market Cap</span>
              <strong>{formatMarketCap(inputProfile?.marketCap ?? null)}</strong>
            </div>
            <div className="candle-stat">
              <span>Industry</span>
              <strong>{displayProfile?.industry ?? '--'}</strong>
            </div>
            <div className="candle-stat">
              <span>Current</span>
              <strong>{formatPrice(snapshot?.currentPrice ?? null)}</strong>
            </div>
            <div className="candle-stat">
              <span>Change</span>
              <strong>{snapshot?.change == null ? '--' : snapshot.change.toFixed(2)}</strong>
            </div>
            <div className="candle-stat">
              <span>Change%</span>
              <strong>{formatPercent(snapshot?.changePercent ?? null)}</strong>
            </div>
            <div className="candle-stat">
              <span>Volume</span>
              <strong>{formatVolume(snapshot?.dayVolume ?? null)}</strong>
            </div>
            <div className="candle-stat">
              <span>State</span>
              <strong>{snapshot?.marketState ?? '--'}</strong>
            </div>
          </div>
        </div>

        <label className="field candle-interval-field">
          <span>Interval</span>
          <select value={interval} onChange={(event) => setInterval(event.target.value as ChartInterval)}>
            {INTERVAL_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
      </div>

      {symbolsError && <div className="error-banner">Symbol directory unavailable: {symbolsError}</div>}

      {error && <div className="error-banner">Candlestick request failed: {error}</div>}
      {loading && <div className="loading-banner">Loading candles...</div>}
      {loadingOlder && <div className="loading-banner">Loading older candles...</div>}

      <div className="chart-wrap">
        <ReactECharts
          ref={chartRef}
          option={chartOption}
          lazyUpdate
          onEvents={onEvents}
          style={{ height: 560, width: '100%' }}
        />
      </div>

      {!hasMoreOlder && <div className="loading-banner">Reached earliest available history for this interval.</div>}
    </section>
  )
}
