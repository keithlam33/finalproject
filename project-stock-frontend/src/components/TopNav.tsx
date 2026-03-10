export type DashboardTab = 'candlestick' | 'heatmap'

interface TopNavProps {
  activeTab: DashboardTab
  onTabChange: (tab: DashboardTab) => void
}

export function TopNav({ activeTab, onTabChange }: TopNavProps) {
  return (
    <nav className="top-nav" aria-label="Dashboard sections">
      <button
        type="button"
        className={activeTab === 'candlestick' ? 'nav-btn active' : 'nav-btn'}
        onClick={() => onTabChange('candlestick')}
      >
        Candlestick
      </button>
      <button
        type="button"
        className={activeTab === 'heatmap' ? 'nav-btn active' : 'nav-btn'}
        onClick={() => onTabChange('heatmap')}
      >
        Heatmap
      </button>
    </nav>
  )
}
