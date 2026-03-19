import { useState } from 'react'
import './App.css'
import { CandlestickPage } from '../features/candlestick/CandlestickPage'
import { TopNav, type DashboardTab } from '../features/dashboard/components/TopNav'
import { HeatmapPage } from '../features/heatmap/HeatmapPage'

function App() {
  const [tab, setTab] = useState<DashboardTab>('heatmap')
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)

  const openCandlestick = (symbol: string) => {
    setSelectedSymbol(symbol.trim().toUpperCase())
    setTab('candlestick')
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1>Stock Dashboard</h1>
        </div>
        <TopNav activeTab={tab} onTabChange={setTab} />
      </header>

      <main className="app-main">
        {tab === 'candlestick' ? (
          <CandlestickPage selectedSymbol={selectedSymbol} />
        ) : (
          <HeatmapPage onOpenCandlestick={openCandlestick} />
        )}
      </main>
    </div>
  )
}

export default App
