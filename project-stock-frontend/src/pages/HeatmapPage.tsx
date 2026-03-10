import { useCallback, useEffect, useMemo, useState } from 'react'
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import { getHeatmap } from '../api/stockApi'
import type { HeatmapDto } from '../types/stock'

const HEATMAP_POLL_MS = 15_000
const MAX_INDUSTRIES = 12
const MAX_LOGO_COUNT = 18
const INDUSTRY_SPACE_FACTORS = [1.38, 1.24, 1.14]

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
  industry?: string
  price?: number | null
  change?: number | null
  changePercent?: number | null
  marketCap?: number | null
  stockCount?: number
  logo?: string | null
  logoKey?: string
}

interface TooltipFormatterParam {
  data?: HeatmapTreeNode
  name?: string
}

interface TreemapClickParam {
  data?: HeatmapTreeNode
}

interface BuildTreeResult {
  treeData: HeatmapTreeNode[]
  richStyles: Record<string, { width: number; height: number; align: 'center'; backgroundColor: { image: string }; borderRadius: number }>
  shownStocks: number
  selectedRows: HeatmapDto[]
  defaultSymbol: string | null
}

interface HeatmapPageProps {
  selectedSymbol: string | null
  onSymbolSelect: (symbol: string | null) => void
  onOpenCandlestick: (symbol: string) => void
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
  if (rankIndex < 5) return 8
  if (rankIndex < 8) return 5
  return 3
}

function industrySpaceFactor(rankIndex: number): number {
  return INDUSTRY_SPACE_FACTORS[rankIndex] ?? 1
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

function sizeScoreFromMarketCap(marketCap: number | null, factor: number): number {
  const cap = positiveMarketCap(marketCap)
  if (cap <= 0) {
    return Math.max(1, 1 * factor)
  }
  return Math.max(1, Math.log10(cap + 1) * factor)
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

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
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
  const selectedByIndustry: Array<{ industry: string; rank: number; rows: HeatmapDto[] }> = []

  rankedIndustries.forEach((entry, rank) => {
    const limit = industryStockLimit(rank)
    const sorted = [...entry.items].sort((a, b) => {
      const capDiff = positiveMarketCap(b.marketCap) - positiveMarketCap(a.marketCap)
      if (capDiff !== 0) return capDiff
      return (b.changePercent ?? Number.NEGATIVE_INFINITY) - (a.changePercent ?? Number.NEGATIVE_INFINITY)
    })
    const chosen = sorted.slice(0, limit)
    selectedRows.push(...chosen)
    selectedByIndustry.push({ industry: entry.industry, rank, rows: chosen })
  })

  const logoSymbols = new Set(
    [...selectedRows]
      .sort((a, b) => positiveMarketCap(b.marketCap) - positiveMarketCap(a.marketCap))
      .filter((row) => Boolean(row.logo))
      .slice(0, MAX_LOGO_COUNT)
      .map((row) => row.symbol),
  )

  const richStyles: BuildTreeResult['richStyles'] = {}
  const treeData: HeatmapTreeNode[] = []

  for (const group of selectedByIndustry) {
    const factor = industrySpaceFactor(group.rank)
    const children: HeatmapTreeNode[] = group.rows.map((row) => {
      const value = sizeScoreFromMarketCap(row.marketCap, factor)
      const showLogo = logoSymbols.has(row.symbol) && Boolean(row.logo)
      const logoKey = showLogo ? `logo_${row.symbol.replace(/[^a-zA-Z0-9_]/g, '_')}` : undefined
      if (logoKey && row.logo) {
        richStyles[logoKey] = {
          width: 14,
          height: 14,
          align: 'center',
          backgroundColor: { image: row.logo },
          borderRadius: 2,
        }
      }

      return {
        name: row.symbol,
        symbol: row.symbol,
        industry: group.industry,
        value,
        price: row.price,
        change: row.change,
        changePercent: row.changePercent,
        marketCap: row.marketCap,
        logo: row.logo,
        logoKey,
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

  const defaultSymbol = selectedByIndustry[0]?.rows[0]?.symbol ?? null

  return {
    treeData,
    richStyles,
    shownStocks: selectedRows.length,
    selectedRows,
    defaultSymbol,
  }
}

function formatLabel(param: TooltipFormatterParam): string {
  const data = param.data
  if (!data) return param.name ?? ''
  if (Array.isArray(data.children)) {
    return `${data.name} ${formatPercentCompact(data.changePercent ?? null)}`
  }
  const symbol = data.symbol ?? data.name
  const change = formatPercentCompact(data.changePercent ?? null)
  if (data.logoKey) {
    return `{${data.logoKey}| }\n${symbol}\n${change}`
  }
  return `${symbol}\n${change}`
}

function formatTooltip(param: TooltipFormatterParam): string {
  const data = param.data
  if (!data) return ''
  if (Array.isArray(data.children)) {
    const industry = escapeHtml(data.name)
    return [
      `<div style="min-width:180px">`,
      `<div style="font-weight:700;margin-bottom:6px">${industry}</div>`,
      `<div>Change: <b>${formatPercent(data.changePercent ?? null)}</b></div>`,
      `<div>Shown stocks: <b>${data.stockCount ?? 0}</b></div>`,
      `</div>`,
    ].join('')
  }

  const symbol = escapeHtml(data.symbol ?? data.name)
  const industry = escapeHtml(data.industry ?? 'Unknown')
  return [
    `<div style="min-width:200px">`,
    `<div style="font-weight:700;margin-bottom:6px">${symbol}</div>`,
    `<div>Industry: <b>${industry}</b></div>`,
    `<div>Price: <b>${formatPrice(data.price ?? null)}</b></div>`,
    `<div>Change: <b>${data.change == null ? '--' : data.change.toFixed(2)}</b></div>`,
    `<div>Change %: <b>${formatPercent(data.changePercent ?? null)}</b></div>`,
    `</div>`,
  ].join('')
}

function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Unknown error'
}

export function HeatmapPage({ selectedSymbol, onSymbolSelect, onOpenCandlestick }: HeatmapPageProps) {
  const [rows, setRows] = useState<HeatmapDto[]>([])
  const [loading, setLoading] = useState<boolean>(true)
  const [error, setError] = useState<string | null>(null)
  const [updatedAt, setUpdatedAt] = useState<number | null>(null)
  const [focusedSymbol, setFocusedSymbol] = useState<string | null>(selectedSymbol)

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

  const { treeData, richStyles, shownStocks, selectedRows, defaultSymbol } = useMemo(() => buildTree(rows), [rows])

  const symbolMap = useMemo(() => {
    const map = new Map<string, HeatmapDto>()
    for (const row of selectedRows) {
      map.set(row.symbol, row)
    }
    return map
  }, [selectedRows])

  useEffect(() => {
    const symbol = selectedSymbol?.trim().toUpperCase() ?? null
    if (!symbol) return
    if (!symbolMap.has(symbol)) return
    setFocusedSymbol(symbol)
  }, [selectedSymbol, symbolMap])

  useEffect(() => {
    if (symbolMap.size === 0) {
      setFocusedSymbol(null)
      onSymbolSelect(null)
      return
    }

    setFocusedSymbol((prev) => {
      if (prev && symbolMap.has(prev)) {
        return prev
      }

      const fromProp = selectedSymbol?.trim().toUpperCase()
      if (fromProp && symbolMap.has(fromProp)) {
        if (fromProp !== selectedSymbol) {
          onSymbolSelect(fromProp)
        }
        return fromProp
      }

      const fallback = defaultSymbol && symbolMap.has(defaultSymbol) ? defaultSymbol : selectedRows[0]?.symbol ?? null
      if (fallback && fallback !== selectedSymbol) {
        onSymbolSelect(fallback)
      }
      return fallback
    })
  }, [defaultSymbol, onSymbolSelect, selectedRows, selectedSymbol, symbolMap])

  const focusedRow = focusedSymbol ? symbolMap.get(focusedSymbol) ?? null : null

  const handleChartClick = useCallback(
    (param: unknown) => {
      const data = (param as TreemapClickParam).data
      if (!data || Array.isArray(data.children) || !data.symbol) return
      const symbol = data.symbol.trim().toUpperCase()
      if (!symbolMap.has(symbol)) return
      setFocusedSymbol(symbol)
      onSymbolSelect(symbol)
    },
    [onSymbolSelect, symbolMap],
  )

  const onEvents = useMemo(
    () => ({
      click: handleChartClick,
    }),
    [handleChartClick],
  )

  const chartOption = useMemo<EChartsOption>(
    () => ({
      animation: false,
      tooltip: {
        trigger: 'item',
        confine: true,
        backgroundColor: 'rgba(2, 6, 23, 0.95)',
        borderColor: '#1e293b',
        textStyle: { color: '#e2e8f0' },
        formatter: (param) => formatTooltip(param as TooltipFormatterParam),
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
            position: 'insideTopLeft',
            color: '#f8fafc',
            fontSize: 14,
            fontWeight: 700,
            lineHeight: 17,
            overflow: 'truncate',
            formatter: (param) => formatLabel(param as TooltipFormatterParam),
            rich: richStyles,
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
    [richStyles, treeData],
  )

  return (
    <section className="panel heatmap-panel">
      <header className="panel-head">
        <div>
          <h2>Heatmap</h2>
          <p>Top 12 industries by total market cap. Auto refresh every 15 seconds.</p>
        </div>
        <div className="panel-meta">
          <span>{updatedAt ? `Updated ${new Date(updatedAt).toLocaleTimeString()}` : 'Waiting for first load'}</span>
          <span>{shownStocks} stocks shown</span>
        </div>
      </header>

      {error && <div className="error-banner">Heatmap request failed: {error}</div>}
      {loading && treeData.length === 0 && <div className="loading-banner">Loading heatmap...</div>}

      <div className="heatmap-scale">
        <span>-4%</span>
        <span>-3%</span>
        <span>-2%</span>
        <span>-1%</span>
        <span>0%</span>
        <span>1%</span>
        <span>2%</span>
        <span>3%</span>
        <span>4%</span>
      </div>

      <div className="heatmap-layout">
        <div className="heatmap-chart-wrap">
          <ReactECharts
            option={chartOption}
            notMerge
            lazyUpdate
            onEvents={onEvents}
            style={{ height: 760, width: '100%' }}
          />
        </div>

        <aside className="heatmap-side">
          <h3>Symbol Profile</h3>

          {focusedRow ? (
            <>
              <div className="side-symbol-head">
                {focusedRow.logo ? (
                  <img className="side-symbol-logo" src={focusedRow.logo} alt={`${focusedRow.symbol} logo`} />
                ) : (
                  <div className="side-symbol-logo side-symbol-logo-fallback">{focusedRow.symbol.slice(0, 2)}</div>
                )}
                <div>
                  <div className="side-symbol-code">{focusedRow.symbol}</div>
                  <div className="side-symbol-name">{focusedRow.companyName ?? focusedRow.symbol}</div>
                </div>
              </div>

              <div className="side-metric-grid">
                <div>
                  <span>Price</span>
                  <strong>{formatPrice(focusedRow.price)}</strong>
                </div>
                <div>
                  <span>Change</span>
                  <strong>{focusedRow.change == null ? '--' : focusedRow.change.toFixed(2)}</strong>
                </div>
                <div>
                  <span>Change%</span>
                  <strong>{formatPercent(focusedRow.changePercent)}</strong>
                </div>
                <div>
                  <span>Market Cap</span>
                  <strong>{formatMarketCap(focusedRow.marketCap)}</strong>
                </div>
                <div>
                  <span>Industry</span>
                  <strong>{focusedRow.industry ?? 'Unknown'}</strong>
                </div>
                <div>
                  <span>State</span>
                  <strong>{focusedRow.marketState ?? '--'}</strong>
                </div>
              </div>

              <button
                type="button"
                className="side-candle-btn"
                onClick={() => onOpenCandlestick(focusedRow.symbol)}
              >
                Candlestick Chart
              </button>
            </>
          ) : (
            <div className="loading-banner">No symbol selected.</div>
          )}
        </aside>
      </div>
    </section>
  )
}
