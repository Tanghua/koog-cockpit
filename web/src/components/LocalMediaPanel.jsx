import { useState } from 'react'
const clock = value => { const seconds=Math.max(0,Math.floor(Number(value)||0)); return `${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}` }
function Icon({name}) {
  const paths={previous:'M6 5v14h3V5H6Zm13 0L9 12l10 7V5Z',next:'M15 5v14h3V5h-3ZM5 5v14l10-7L5 5Z',play:'M8 5v14l12-7L8 5Z',pause:'M6 5h4v14H6V5Zm8 0h4v14h-4V5Z',note:'M9 3v12.6a4 4 0 1 0 2 3.4V7l9-2V1L9 3Z',refresh:'M18.4 5.6A9 9 0 1 0 21 12h-2a7 7 0 1 1-2-4.9L13 11h9V2l-3.6 3.6Z'}
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={paths[name]} fill="currentColor"/></svg>
}
export default function LocalMediaPanel({state={}, error, onPlay, onPause, onNext, onPrev, onRefresh, busy}) {
  const [artFailed,setArtFailed]=useState('')
  const ready=state.connected&&!error
  const duration=Number(state.duration)||0,position=Math.min(duration||Infinity,Number(state.position)||0)
  const artwork=state.artwork?.startsWith('data:image/')&&artFailed!==state.artwork ? state.artwork : ''
  return <section className="local-player" aria-label="车机音乐播放器">
    <header className="player-heading"><div><span className="player-eyebrow">NOW PLAYING</span><h2>随行音乐</h2></div><span className={`player-connection ${ready?'connected':''}`}><i/>{ready?'车机媒体已连接':'媒体状态待确认'}</span></header>
    <div className="player-body">
      <div className={`player-art ${state.playing?'is-playing':''}`}>
        {artwork?<img src={artwork} alt="当前歌曲专辑封面" onError={()=>setArtFailed(state.artwork)}/>:<div className="record-placeholder"><div className="record-groove"><Icon name="note"/></div><span>本地音源</span></div>}
        <span className="player-art-label">{state.playing?'正在播放':'音乐随时待命'}</span>
      </div>
      <div className="player-details">
        <span className="player-source">车机本地媒体 · MediaX</span>
        <h3 title={state.title}>{state.title||'等待车机返回歌曲'}</h3>
        <p className="player-artist">{state.artist||'歌手信息未提供'}</p>
        <p className="player-album">专辑 · {state.album||'暂未提供'}</p>
        <div className="player-progress"><progress aria-label="当前播放进度" value={position} max={duration||1}/><div><time>{clock(position)}</time><span>{duration?clock(duration):'时长未提供'}</span></div></div>
        <div className="player-controls">
          <button aria-label="上一首" title="上一首" disabled={busy||!ready||!state.canPrevious} onClick={onPrev}><Icon name="previous"/></button>
          <button className="player-toggle" aria-label={state.playing?'暂停音乐':'播放音乐'} title={state.playing?'暂停音乐':'播放音乐'} disabled={busy||!ready||!state.canPlayPause} onClick={state.playing?onPause:onPlay}><Icon name={state.playing?'pause':'play'}/></button>
          <button aria-label="下一首" title="下一首" disabled={busy||!ready||!state.canNext} onClick={onNext}><Icon name="next"/></button>
        </div>
        <p className="player-feedback" role="status">{error||(busy?'正在等待车机回执…':state.playbackState===2?'车机正在缓冲…':state.playing?'正在播放车机当前音源':'点击播放，继续车机当前音源')}</p>
      </div>
    </div>
    <footer className="player-bottom"><div><strong>听见旅途的节奏</strong><span>支持语音说“播放音乐”“暂停音乐”“下一首”</span></div><button onClick={onRefresh} disabled={busy} aria-label="刷新媒体状态"><Icon name="refresh"/>刷新状态</button></footer>
  </section>
}
