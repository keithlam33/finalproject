import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import { getCandlestick } from '../api/stockApi'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import type { CandleDto, CandlestickChartDto, ChartInterval } from '../types/stock'
import { INTERVAL_OPTIONS } from '../types/stock'

const DEFAULT_WINDOW = 240
const MIN_WINDOW = 40
const CANDLE_POLL_MS = 10_000
const SYMBOL_DEBOUNCE_MS = 300

interface ZoomRange {
  start: number
  end: number
}

interface DataZoomEvent {
  start?: number
  end?: number
  batch?: Array<{ start?: number; end?: number }>
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

function toTimeLabel(epochSec: number): string {
  return new Date(epochSec * 1000).toLocaleString()
}

interface CandlestickPageProps {
  selectedSymbol?: string | null
}

export function CandlestickPage({ selectedSymbol }: CandlestickPageProps) {
  const [symbolInput, setSymbolInput] = useState<string>(() => {
    const symbol = selectedSymbol?.trim().toUpperCase()
    return symbol && symbol.length > 0 ? symbol : 'TSLA'
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

  const chartRef = useRef<ReactECharts>(null)
  const windowSizeRef = useRef<number>(windowSize)
  const loadingOlderRef = useRef<boolean>(false)
  const olderAbortRef = useRef<AbortController | null>(null)

  const debouncedSymbol = useDebouncedValue(symbolInput.trim().toUpperCase(), SYMBOL_DEBOUNCE_MS)
  const isAtRightEdge = zoomRange.end >= 98

  useEffect(() => {
    const symbol = selectedSymbol?.trim().toUpperCase()
    if (!symbol || symbol === symbolInput) return
    setSymbolInput(symbol)
  }, [selectedSymbol, symbolInput])

  useEffect(() => {
    windowSizeRef.current = windowSize
  }, [windowSize])

  useEffect(() => {
    return () => {
      olderAbortRef.current?.abort()
    }
  }, [])

  const loadOlder = useCallback(async () => {
    if (!debouncedSymbol || !hasMoreOlder || loadingOlderRef.current) {
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
        symbol: debouncedSymbol,
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
  }, [candles, debouncedSymbol, hasMoreOlder, interval])

  useEffect(() => {
    if (!debouncedSymbol) {
      setCandles([])
      setLoading(false)
      return
    }

    let disposed = false
    const controller = new AbortController()

    setLoading(true)
    setError(null)
    setHasMoreOlder(true)

    const requestWindow = Math.max(MIN_WINDOW, windowSizeRef.current)

    void getCandlestick({
      symbol: debouncedSymbol,
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
  }, [debouncedSymbol, interval])

  useEffect(() => {
    if (!debouncedSymbol) return

    let disposed = false
    let controller: AbortController | null = null

    const pollLatest = async () => {
      controller?.abort()
      controller = new AbortController()

      try {
        const dto = await getCandlestick({
          symbol: debouncedSymbol,
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
  }, [debouncedSymbol, interval, isAtRightEdge])

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
          axisLabel: { color: '#6b7280' },
        },
        {
          gridIndex: 1,
          scale: true,
          splitLine: { show: false },
          axisLabel: { color: '#6b7280' },
        },
      ],
      dataZoom: [
        {
          type: 'inside',
          xAxisIndex: [0, 1],
          start: zoomRange.start,
          end: zoomRange.end,
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

  return (
    <section className="panel">
      <header className="panel-head">
        <div>
          <h2>Candlestick</h2>
          <p>Initial window: 240 candles. Latest candle updates every 10 seconds.</p>
        </div>
        <div className="panel-meta">
          <span>Visible window: {windowSize}</span>
          <span>{isAtRightEdge ? 'Following latest' : 'Historical view'}</span>
        </div>
      </header>

      <div className="control-row">
        <label className="field">
          <span>Symbol</span>
          <input
            value={symbolInput}
            onChange={(event) => setSymbolInput(event.target.value.toUpperCase())}
            placeholder="TSLA"
            maxLength={12}
          />
        </label>
        <label className="field">
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

      <div className="quote-strip">
        <div>
          <span className="quote-label">Price</span>
          <strong>{formatPrice(snapshot?.currentPrice ?? null)}</strong>
        </div>
        <div>
          <span className="quote-label">Change%</span>
          <strong>{formatPercent(snapshot?.changePercent ?? null)}</strong>
        </div>
        <div>
          <span className="quote-label">State</span>
          <strong>{snapshot?.marketState ?? '--'}</strong>
        </div>
        <div>
          <span className="quote-label">Delay (sec)</span>
          <strong>{snapshot?.delaySec ?? '--'}</strong>
        </div>
      </div>

      {error && <div className="error-banner">Candlestick request failed: {error}</div>}
      {loading && <div className="loading-banner">Loading candles...</div>}
      {loadingOlder && <div className="loading-banner">Loading older candles...</div>}

      <div className="chart-wrap">
        <ReactECharts
          ref={chartRef}
          option={chartOption}
          notMerge
          lazyUpdate
          onEvents={onEvents}
          style={{ height: 560, width: '100%' }}
        />
      </div>

      {!hasMoreOlder && <div className="loading-banner">Reached earliest available history for this interval.</div>}
    </section>
  )
}
