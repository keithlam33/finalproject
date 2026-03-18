import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import { getHeatmap } from '../../shared/api/stockApi'
import type { HeatmapDto } from '../../shared/types/stock'

const HEATMAP_POLL_MS = 15_000
const MAX_INDUSTRIES = 18

interface HeatmapTreeNode {
  name: string
  value: number
  children?: HeatmapTreeNode[]
  itemStyle?: {
    color?: string
    borderColor?: string
    borderWidth?: number
  }
  symbol?: string
  companyName?: string | null
  industry?: string
  price?: number | null
  change?: number | null
  changePercent?: number | null
  marketCap?: number | null
  marketState?: string | null
  stockCount?: number
  logo?: string | null
}

interface TooltipFormatterParam {
  data?: HeatmapTreeNode
  name?: string
}

interface TreemapClickParam {
  data?: HeatmapTreeNode
  event?: {
    event?: {
      offsetX?: number
      offsetY?: number
      zrX?: number
      zrY?: number
    }
  }
}

interface BuildTreeResult {
  treeData: HeatmapTreeNode[]
  shownStocks: number
  selectedRows: HeatmapDto[]
}

interface HeatmapPageProps {
  onOpenCandlestick: (symbol: string) => void
}

interface HoverCardPosition {
  left: number
  top: number
}

const HOVER_CARD_WIDTH = 280
const HOVER_CARD_HEIGHT = 230
const HOVER_CARD_OFFSET = 16

function formatPrice(value: number | null): string {
  if (value == null) return '--'
  return value.toFixed(2)
}

function formatPercent(value: number | null): string {
  if (value == null) return '--'
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function formatMarketCap(value: number | null): string {
  if (value == null || !Number.isFinite(value)) return '--'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}T`
  if (value >= 1_000) return `${(value / 1_000).toFixed(2)}B`
  return `${value.toFixed(2)}M`
}

function formatPercentCompact(value: number | null): string {
  if (value == null) return '--'
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(1)}%`
}

function normalizeIndustry(industry: string | null): string {
  const trimmed = industry?.trim()
  return trimmed && trimmed.length > 0 ? trimmed : 'Unknown'
}

function positiveMarketCap(value: number | null): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0
}

function weightedIndustryChange(rows: HeatmapDto[]): number | null {
  let numerator = 0
  let denominator = 0
  for (const row of rows) {
    if (row.changePercent == null || !Number.isFinite(row.changePercent)) {
      continue
    }
    const cap = positiveMarketCap(row.marketCap)
    const weight = cap > 0 ? cap : 1
    numerator += row.changePercent * weight
    denominator += weight
  }
  if (denominator <= 0) {
    return null
  }
  return numerator / denominator
}

function industryStockLimit(rankIndex: number): number {
  if (rankIndex < 6) return 7
  if (rankIndex < 12) return 4
  return 3
}

function colorForChange(changePercent: number | null): string {
  if (changePercent == null || !Number.isFinite(changePercent)) return '#334155'
  if (changePercent < 0) {
    if (changePercent <= -3) return '#7f1d1d' // darkest red
    if (changePercent <= -2) return '#991b1b' // dark red
    if (changePercent <= -1) return '#b91c1c' // medium red
    return '#ef4444' // light red
  }
  if (changePercent > 0) {
    if (changePercent >= 3) return '#065f46' // darkest green
    if (changePercent >= 2) return '#047857' // dark green
    if (changePercent >= 1) return '#059669' // medium green
    return '#34d399' // light green
  }
  return '#1f2937'
}

function sizeScoreFromMarketCap(marketCap: number | null): number {
  const cap = positiveMarketCap(marketCap)
  if (cap <= 0) {
    return 1
  }
  return Math.max(1, Math.sqrt(cap))
}

function shouldShowGoogleSingle(rows: HeatmapDto[]): boolean {
  return rows.some((row) => row.symbol === 'GOOGL')
}

function filterForDisplay(rows: HeatmapDto[]): HeatmapDto[] {
  const hideGoog = shouldShowGoogleSingle(rows)
  if (!hideGoog) {
    return rows
  }
  return rows.filter((row) => row.symbol !== 'GOOG')
}

function buildTree(rows: HeatmapDto[]): BuildTreeResult {
  const cleaned = filterForDisplay(rows)

  const byIndustry = new Map<string, HeatmapDto[]>()
  for (const row of cleaned) {
    const symbol = row.symbol?.trim()
    if (!symbol) continue
    const industry = normalizeIndustry(row.industry)
    const bucket = byIndustry.get(industry)
    if (bucket) {
      bucket.push({ ...row, symbol })
    } else {
      byIndustry.set(industry, [{ ...row, symbol }])
    }
  }

  const rankedIndustries = [...byIndustry.entries()]
    .map(([industry, items]) => ({
      industry,
      items,
      totalCap: items.reduce((sum, item) => sum + positiveMarketCap(item.marketCap), 0),
    }))
    .sort((a, b) => b.totalCap - a.totalCap)
    .slice(0, MAX_INDUSTRIES)

  const selectedRows: HeatmapDto[] = []
  const selectedByIndustry: Array<{ industry: string; rows: HeatmapDto[] }> = []

  rankedIndustries.forEach((entry, rank) => {
    const limit = industryStockLimit(rank)
    const sorted = [...entry.items].sort((a, b) => {
      const capDiff = positiveMarketCap(b.marketCap) - positiveMarketCap(a.marketCap)
      if (capDiff !== 0) return capDiff
      return (b.changePercent ?? Number.NEGATIVE_INFINITY) - (a.changePercent ?? Number.NEGATIVE_INFINITY)
    })
    const chosen = sorted.slice(0, limit)
    selectedRows.push(...chosen)
    selectedByIndustry.push({ industry: entry.industry, rows: chosen })
  })

  const treeData: HeatmapTreeNode[] = []

  for (const group of selectedByIndustry) {
    const children: HeatmapTreeNode[] = group.rows.map((row) => {
      const value = sizeScoreFromMarketCap(row.marketCap)
      return {
        name: row.symbol,
        symbol: row.symbol,
        companyName: row.companyName,
        industry: group.industry,
        value,
        price: row.price,
        change: row.change,
        changePercent: row.changePercent,
        marketCap: row.marketCap,
        marketState: row.marketState,
        logo: row.logo,
        itemStyle: {
          color: colorForChange(row.changePercent),
          borderColor: '#0b1220',
          borderWidth: 1,
        },
      }
    })

    const industryValue = children.reduce((sum, child) => sum + child.value, 0)
    treeData.push({
      name: group.industry,
      value: industryValue,
      stockCount: children.length,
      changePercent: weightedIndustryChange(group.rows),
      children,
      itemStyle: {
        borderColor: '#0f172a',
        borderWidth: 2,
      },
    })
  }

  return {
    treeData,
    shownStocks: selectedRows.length,
    selectedRows,
  }
}

function formatLabel(param: TooltipFormatterParam): string {
  const data = param.data
  if (!data) return param.name ?? ''
  if (Array.isArray(data.children)) {
    const name = data.name ?? param.name
    if (!name) return ''
    return `${name} ${formatPercentCompact(data.changePercent ?? null)}`
  }
  const symbol = data.symbol ?? data.name
  const change = formatPercentCompact(data.changePercent ?? null)
  const score = typeof data.value === 'number' ? data.value : 0
  if (score < 2.2) {
    return symbol
  }
  if (score < 3.4) {
    return `${symbol}\n${change}`
  }
  return `${symbol}\n${change}`
}

function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Unknown error'
}

export function HeatmapPage({ onOpenCandlestick }: HeatmapPageProps) {
  const [rows, setRows] = useState<HeatmapDto[]>([])
  const [loading, setLoading] = useState<boolean>(true)
  const [error, setError] = useState<string | null>(null)
  const [updatedAt, setUpdatedAt] = useState<number | null>(null)
  const [hoveredSymbol, setHoveredSymbol] = useState<string | null>(null)
  const [hoverCardPosition, setHoverCardPosition] = useState<HoverCardPosition | null>(null)
  const chartWrapRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    let disposed = false
    let controller: AbortController | null = null

    const load = async () => {
      controller?.abort()
      controller = new AbortController()

      try {
        const data = await getHeatmap(controller.signal)
        if (disposed) return
        setRows(data)
        setError(null)
        setUpdatedAt(Date.now())
      } catch (err) {
        if (controller.signal.aborted || disposed) return
        setError(toErrorMessage(err))
      } finally {
        if (!disposed) {
          setLoading(false)
        }
      }
    }

    void load()
    const intervalId = window.setInterval(() => {
      void load()
    }, HEATMAP_POLL_MS)

    return () => {
      disposed = true
      window.clearInterval(intervalId)
      controller?.abort()
    }
  }, [])

  const { treeData, shownStocks, selectedRows } = useMemo(() => buildTree(rows), [rows])

  const symbolMap = useMemo(() => {
    const map = new Map<string, HeatmapDto>()
    for (const row of selectedRows) {
      map.set(row.symbol.trim().toUpperCase(), row)
    }
    return map
  }, [selectedRows])

  useEffect(() => {
    if (!hoveredSymbol) return
    if (!symbolMap.has(hoveredSymbol)) {
      setHoveredSymbol(null)
    }
  }, [hoveredSymbol, symbolMap])

  const hoveredRow = hoveredSymbol ? symbolMap.get(hoveredSymbol) ?? null : null

  const clampHoverCardPosition = useCallback((x: number, y: number): HoverCardPosition => {
    const wrap = chartWrapRef.current
    if (!wrap) {
      return { left: x + HOVER_CARD_OFFSET, top: y + HOVER_CARD_OFFSET }
    }

    const maxLeft = Math.max(12, wrap.clientWidth - HOVER_CARD_WIDTH - 12)
    const maxTop = Math.max(12, wrap.clientHeight - HOVER_CARD_HEIGHT - 12)

    let left = x + HOVER_CARD_OFFSET
    let top = y + HOVER_CARD_OFFSET

    if (left > maxLeft) {
      left = Math.max(12, x - HOVER_CARD_WIDTH - HOVER_CARD_OFFSET)
    }
    if (top > maxTop) {
      top = Math.max(12, y - HOVER_CARD_HEIGHT - HOVER_CARD_OFFSET)
    }

    return {
      left: Math.min(Math.max(12, left), maxLeft),
      top: Math.min(Math.max(12, top), maxTop),
    }
  }, [])

  const extractHoverPoint = useCallback((param: TreemapClickParam): HoverCardPosition | null => {
    const raw = param.event?.event
    if (!raw) {
      return null
    }

    const x = raw.offsetX ?? raw.zrX
    const y = raw.offsetY ?? raw.zrY
    if (typeof x !== 'number' || typeof y !== 'number') {
      return null
    }

    return clampHoverCardPosition(x, y)
  }, [clampHoverCardPosition])

  const handleChartClick = useCallback(
    (param: unknown) => {
      const data = (param as TreemapClickParam).data
      if (!data || Array.isArray(data.children) || !data.symbol) return
      const symbol = data.symbol.trim().toUpperCase()
      onOpenCandlestick(symbol)
    },
    [onOpenCandlestick],
  )

  const handleChartHover = useCallback((param: unknown) => {
    const eventParam = param as TreemapClickParam
    const data = eventParam.data
    if (!data || Array.isArray(data.children) || !data.symbol) {
      setHoveredSymbol(null)
      setHoverCardPosition(null)
      return
    }
    const symbol = data.symbol.trim().toUpperCase()
    setHoveredSymbol((prev) => (prev === symbol ? prev : symbol))
    const nextPosition = extractHoverPoint(eventParam)
    if (nextPosition) {
      setHoverCardPosition(nextPosition)
    }
  }, [extractHoverPoint])

  const handleChartLeave = useCallback(() => {
    setHoveredSymbol(null)
    setHoverCardPosition(null)
  }, [])

  const onEvents = useMemo(
    () => ({
      click: handleChartClick,
      mouseover: handleChartHover,
      globalout: handleChartLeave,
    }),
    [handleChartClick, handleChartHover, handleChartLeave],
  )

  const chartOption = useMemo<EChartsOption>(
    () => ({
      animation: false,
      tooltip: {
        show: false,
      },
      series: [
        {
          type: 'treemap',
          roam: false,
          nodeClick: false,
          breadcrumb: { show: false },
          visibleMin: 12,
          squareRatio: 1.15,
          sort: 'desc',
          data: treeData,
          label: {
            show: true,
            position: 'inside',
            color: '#f8fafc',
            fontSize: 14,
            fontWeight: 700,
            lineHeight: 17,
            overflow: 'truncate',
            align: 'center',
            verticalAlign: 'middle',
            formatter: (param) => formatLabel(param as TooltipFormatterParam),
          },
          upperLabel: {
            show: true,
            height: 30,
            color: '#e2e8f0',
            fontSize: 18,
            fontWeight: 700,
            overflow: 'truncate',
            formatter: (param) => formatLabel(param as TooltipFormatterParam),
          },
          itemStyle: {
            borderColor: '#0b1220',
            borderWidth: 2,
            gapWidth: 2,
          },
          levels: [
            {
              itemStyle: {
                borderColor: '#0f172a',
                borderWidth: 3,
                gapWidth: 3,
              },
              upperLabel: { show: true, color: '#e2e8f0' },
            },
            {
              itemStyle: {
                borderColor: '#111827',
                borderWidth: 1,
                gapWidth: 1,
              },
            },
          ],
        },
      ],
    }),
    [treeData],
  )

  return (
    <section className="panel heatmap-panel">
      <header className="panel-head">
        <div>
          <h2>Heatmap</h2>
          <p>Top 18 industries by market cap. Hover for details, click a tile to open candlestick.</p>
        </div>
        <div className="panel-meta">
          <span>{updatedAt ? `Updated ${new Date(updatedAt).toLocaleTimeString()}` : 'Waiting for first load'}</span>
          <span>{shownStocks} stocks shown</span>
        </div>
      </header>

      {error && <div className="error-banner">Heatmap request failed: {error}</div>}
      {loading && treeData.length === 0 && <div className="loading-banner">Loading heatmap...</div>}

      <div ref={chartWrapRef} className="heatmap-chart-wrap heatmap-chart-wrap--full">
        {hoveredRow && hoverCardPosition && (
          <div
            className="heatmap-hover-card"
            style={{ left: `${hoverCardPosition.left}px`, top: `${hoverCardPosition.top}px` }}
          >
            <div className="heatmap-hover-head">
              {hoveredRow.logo ? (
                <img className="heatmap-hover-logo" src={hoveredRow.logo} alt={`${hoveredRow.symbol} logo`} />
              ) : (
                <div className="heatmap-hover-logo heatmap-hover-logo-fallback">
                  {hoveredRow.symbol.slice(0, 2)}
                </div>
              )}
              <div>
                <div className="heatmap-hover-symbol">{hoveredRow.symbol}</div>
                <div className="heatmap-hover-name">{hoveredRow.companyName ?? hoveredRow.symbol}</div>
              </div>
            </div>

            <div className="heatmap-hover-grid">
              <div>
                <span>Price</span>
                <strong>{formatPrice(hoveredRow.price)}</strong>
              </div>
              <div>
                <span>Change%</span>
                <strong>{formatPercent(hoveredRow.changePercent)}</strong>
              </div>
              <div>
                <span>Change</span>
                <strong>{hoveredRow.change == null ? '--' : hoveredRow.change.toFixed(2)}</strong>
              </div>
              <div>
                <span>Market Cap</span>
                <strong>{formatMarketCap(hoveredRow.marketCap)}</strong>
              </div>
              <div>
                <span>Industry</span>
                <strong>{hoveredRow.industry ?? 'Unknown'}</strong>
              </div>
              <div>
                <span>State</span>
                <strong>{hoveredRow.marketState ?? '--'}</strong>
              </div>
            </div>

            <div className="heatmap-hover-tip">Click tile to open candlestick</div>
          </div>
        )}
        <ReactECharts
          option={chartOption}
          notMerge
          lazyUpdate
          onEvents={onEvents}
          style={{ height: 860, width: '100%' }}
        />
      </div>
    </section>
  )
}
