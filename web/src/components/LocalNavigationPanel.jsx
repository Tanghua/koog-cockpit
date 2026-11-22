import { useState } from 'react'
import './LocalNavigationPanel.css'

const distance = meters => Number(meters) >= 0
  ? Number(meters) >= 1000 ? `${(Number(meters) / 1000).toFixed(1)} 公里` : `${meters} 米`
  : '—'
const duration = seconds => {
  const minutes = Math.ceil(Number(seconds) / 60)
  if (!Number.isFinite(minutes) || minutes < 0) return '—'
  return minutes < 60 ? `${minutes} 分钟` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`
}

export default function LocalNavigationPanel({ navigation, invoke }) {
  const [destination, setDestination] = useState('')
  const [selected, setSelected] = useState(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const { search, guidance, routes, status } = navigation
  const searchResults = search?.type === 'results'
  const searching = search?.type === 'searching'
  const candidates = Array.isArray(search?.candidates) ? search.candidates : []
  const place = selected && selected.search === search ? candidates[selected.index] : null
  const activeRoutes = place && routes?.index === selected.index ? routes : null
  const run = async (method, args, success = '') => {
    if (busy) return
    setBusy(true)
    setMessage('')
    try { await invoke(method, args); setMessage(success) }
    catch (error) { setMessage(error.message) }
    finally { setBusy(false) }
  }
  const requestSearch = (keyword, city) => run('localNavigationCandidates', { destination: keyword, city })

  return <section className="local-navigation-panel" aria-label="车机导航">
    <header><h2>车机导航</h2><button onClick={() => run('localMap')} disabled={busy}>打开高德</button></header>
    <form onSubmit={event => {
      event.preventDefault()
      if (destination.trim() && !searching) requestSearch(destination.trim())
    }}>
      <input aria-label="目的地" placeholder="搜索目的地，也可以说“导航去杭州西湖”" value={destination}
        maxLength={100} onChange={event => setDestination(event.target.value)} />
      <button disabled={busy || searching || !destination.trim()}>搜索</button>
    </form>
    <p role="status">{message || (searching ? `正在搜索 ${search.keyword}…` : search?.message
      || (searchResults ? candidates.length ? `“${search.keyword}” · ${candidates.length} 个结果` : '未找到匹配地点' : '由车机高德提供搜索与导航'))}</p>
    {guidance && <div className="local-navigation-summary">
      <strong>{guidance.nextRoad ? `下一道路：${guidance.nextRoad}` : '导航进行中'}</strong>
      <span>下一引导点 {distance(guidance.nextTurnMeters)}</span>
      <span>剩余 {distance(guidance.remainingMeters)} · {duration(guidance.remainingSeconds)}</span>
    </div>}
    {!guidance && status?.state === 8 && <p>高德导航已开始，正在等待引导信息。</p>}
    {place && <div className="local-navigation-actions">
      <strong>{place.name}</strong>
      <button disabled={busy || activeRoutes?.pending} onClick={() => run('localNavigationPlan', { index: selected.index })}>规划路线</button>
      <button disabled={busy} onClick={() => run('localNavigationStart', { index: selected.index }, '已向高德发送导航请求')}>开始导航</button>
    </div>}
    {activeRoutes?.pending && <p role="status">正在等待高德算路结果…</p>}
    {activeRoutes?.message && <p role="status">{activeRoutes.message}</p>}
    {Array.isArray(activeRoutes?.paths) && <div className="local-navigation-summary" aria-label="路线方案">
      <strong>路线方案</strong>
      {activeRoutes.paths.slice(0, 3).map((route, index) => <span key={index}>{route.method || `路线 ${index + 1}`} · {distance(route.distance ?? route.ditance ?? route.distance_AUTO)} · {duration(route.time)}</span>)}
    </div>}
    {candidates.length > 0 && <ul aria-label="高德候选地点">
      {candidates.map((item, index) => {
        const city = item?.city_suggestion_name
        if (!item?.name && !city) return null
        return <li key={`${item.poiid || item.name || city}-${index}`}>
          <button aria-pressed={Boolean(place && selected.index === index)}
            disabled={busy} onClick={() => city ? requestSearch(search.keyword, city) : setSelected({ search, index })}>
            <strong>{item.name || city}</strong>
            {item.address && <span>{item.address}</span>}
          </button>
        </li>
      })}
    </ul>}
  </section>
}
