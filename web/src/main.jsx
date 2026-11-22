import React, { Component, Suspense, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import TopBar from './components/TopBar'
import RuntimeBrand from './components/RuntimeBrand'
import VehiclePanel from './components/VehiclePanel'
import LocalNavigationPanel from './components/LocalNavigationPanel'
import LocalMediaPanel from './components/LocalMediaPanel'
import { invoke } from './bridge'
import { useRuntimeVoice } from './useRuntimeVoice'
import './qwen.css'
import './runtime.css'

class VehicleBoundary extends Component {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  render() { return this.state.failed ? <aside className="vehicle-fallback">车辆示意图暂不可用<p>本地指令仍可使用</p></aside> : this.props.children }
}
const statusText = {running:'执行中',accepted:'等待车机确认',succeeded:'已完成',failed:'未完成',partial:'部分完成',unknown:'结果待核对'}
const navActions = {localMap:'map',localNavigationCandidates:'navigationCandidates',localNavigationPlan:'navigationPlan',localNavigationStart:'navigationStart'}
function App() {
  const [state,setState] = useState({tasks:[],media:{connected:false},navigation:{},commuteEnabled:true,home:''})
  const [tab,setTab] = useState('navigation')
  const [text,setText] = useState('')
  const [notice,setNotice] = useState('文字指令可用，也可点击麦克风开始对话')
  const [mediaError,setMediaError] = useState('')
  const [busy,setBusy] = useState(false)
  const [home,setHome] = useState('')
  const [enabled,setEnabled] = useState(true)
  const voice = useRuntimeVoice({onMedia:()=>setTab('media'),onNavigation:()=>setTab('navigation')})
  const refreshMedia = () => invoke('mediaRefresh').then(value=>{setState(value);setMediaError('')}).catch(error=>setMediaError(error.message))
  useEffect(()=>{
    if(tab!=='media')return
    let stopped=false,timer
    async function poll(){await refreshMedia();if(!stopped)timer=setTimeout(poll,1500)}
    poll();return()=>{stopped=true;clearTimeout(timer)}
  },[tab])
  const latest = state.tasks.at(-1)
  const brand = state.framework?.startsWith('Koog') ? 'Koog' : 'Agent Runtime'
  useEffect(() => { document.title = `${brand} 智能座舱` }, [brand])
  useEffect(() => {
    const onState = event => setState(event.detail)
    window.addEventListener('cockpit-state',onState)
    invoke('ready').then(value => {setState(value);setHome(value.home || '');setEnabled(value.commuteEnabled)}).catch(error=>setNotice(error.message))
    return ()=>window.removeEventListener('cockpit-state',onState)
  },[])
  async function request(method,params) {
    setBusy(true)
    try { const value=await invoke(method,params);setState(value);setNotice('指令已提交，请查看车机回执');return value }
    catch(error){setNotice(error.message);throw error}
    finally{setBusy(false)}
  }
  const submit = command => request('submit',{text:command}).catch(()=>{})
  const local = (action,arguments_={})=>request('local',{action,arguments:arguments_})
  const safeLocal = action => local(action).catch(()=>{})
  const running = state.tasks.some(task=>task.status==='running')
  return <div className="device runtime-device"><div className="screen runtime-screen">
    <TopBar brand={brand} />
    <div className="runtime-main">
      <VehicleBoundary><Suspense fallback={<aside className="vehicle-fallback">正在载入车辆示意图…</aside>}>
        <VehiclePanel carState={{}} voiceAvailable voiceMuted={voice.muted} voiceState={voice.state} voiceError={voice.error} inputLevel={voice.inputLevel} persona="座舱助手" onSelectPersona={()=>setNotice('当前使用座舱助手')} onToggleVoiceMute={voice.toggle} onOpenSettings={()=>setTab('settings')} />
      </Suspense></VehicleBoundary>
      <main className="runtime-workspace">
        <nav className="runtime-tabs" aria-label="座舱功能">{[['navigation','导航'],['media','媒体'],['tasks','任务记录'],['skills','我的能力'],['settings','设置']].map(([id,label])=><button key={id} aria-current={tab===id?'page':undefined} onClick={()=>setTab(id)}>{label}</button>)}</nav>
        <div className="runtime-content">
          {tab==='navigation' && <LocalNavigationPanel navigation={state.navigation} invoke={(method,args)=>local(navActions[method],args)} />}
          {tab==='media' && <LocalMediaPanel state={state.media} busy={busy||running} error={mediaError||(!state.media.connected?'尚未取得车机媒体状态':'')} onRefresh={refreshMedia} onPlay={()=>safeLocal('media_play')} onPause={()=>safeLocal('media_pause')} onNext={()=>safeLocal('media_next')} onPrev={()=>safeLocal('media_previous')} />}
          {tab==='tasks' && <section className="runtime-page"><h2>任务记录</h2>{state.tasks.length===0?<p>还没有任务。输入指令后，这里会显示实际执行结果。</p>:[...state.tasks].reverse().map(task=><article className="task-card" key={task.id}><header><strong>{task.title}</strong><span className={`task-status ${task.status}`}>{statusText[task.status]||task.status}</span></header><p>{task.summary}</p><ul>{(task.steps||[]).map((step,index)=><li key={index}>{step.action} · {statusText[step.status]}<small>{step.summary}</small></li>)}</ul></article>)}</section>}
          {tab==='skills' && <section className="runtime-page"><h2>我的能力</h2><form className="ability-form" onSubmit={event=>{event.preventDefault();request('settings',{home,commuteEnabled:enabled}).then(()=>setNotice('下班模式已保存')).catch(()=>{})}}><h3>下班模式</h3><p>先在高德中搜索回家目的地，再请求播放车机音乐。搜索失败时，媒体步骤仍可继续。</p><label>回家目的地<input value={home} maxLength={100} placeholder="例如：杭州西湖" onChange={event=>setHome(event.target.value)}/></label><label className="switch-row"><input type="checkbox" checked={enabled} onChange={event=>setEnabled(event.target.checked)}/>启用下班模式</label><div><button className="primary" disabled={busy}>保存配置</button><button type="button" disabled={busy||running||!state.commuteEnabled||!state.home} onClick={()=>submit('开启下班模式')}>运行下班模式</button></div></form></section>}
          {tab==='settings' && <section className="runtime-page"><h2>座舱设置</h2><dl><dt>任务引擎</dt><dd>{state.framework||'正在连接'}</dd><dt>指令方式</dt><dd>本地文字指令 + 在线实时语音对话（需联网）</dd><dt>导航</dt><dd>高德车机标准广播 · 搜索、路线与引导信息</dd><dt>媒体</dt><dd>车机本地媒体服务</dd></dl><p>导航区域展示高德返回的信息，尚未嵌入完整地图画面。</p><button onClick={()=>request('ready').catch(()=>{})}>刷新本地状态</button><details><summary>开源与素材</summary><p>界面基于 Qwen Audio Agent Smart Cockpit，Apache-2.0。车辆模型 Tesla 2018 Model 3，作者 Ameer Studio，CC-BY-4.0；模型来源：https://sketchfab.com/3d-models/tesla-2018-model-3-5ef9b845aaf44203b6d04e2c677e444f 。本应用调整了展示与交互代码。</p></details></section>}
        </div>
        <section className="runtime-command" aria-label="助手指令">
          {tab!=='media'&&<div className="quick-actions">{['查询媒体状态','暂停音乐','打开地图'].map(command=><button key={command} disabled={running||busy} onClick={()=>submit(command)}>{command}</button>)}</div>}
          <form onSubmit={event=>{event.preventDefault();if(text.trim())submit(text.trim())}}><input aria-label="输入座舱指令" value={text} onChange={event=>setText(event.target.value)} placeholder="输入指令，例如：导航去杭州西湖" maxLength={200}/><button className="primary" disabled={busy||running||!text.trim()}>发送</button>{running&&<button type="button" onClick={()=>request('cancel').catch(()=>{})}>取消</button>}</form>
          <p role="status">{!voice.muted||voice.error ? voice.status : latest?.status==='running'?'正在执行…':notice}</p>
          {voice.messages.length>0&&<div className="voice-transcript"><strong>{voice.messages.at(-1).role==='user'?'你':'助手'}</strong><span>{voice.messages.at(-1).text}</span></div>}
        </section>
      </main>
    </div>
    <footer className="runtime-footer"><span className="runtime-assistant"><RuntimeBrand name={brand} compact /></span><button onClick={()=>safeLocal('aircon')}>空调面板</button><div className="runtime-current"><span className={`status-dot ${latest?.status==='running'?'busy':''}`}/>{latest ? `${statusText[latest.status]} · ${latest.summary}` : '本地助手已就绪'}</div><button onClick={()=>setTab('tasks')}>查看任务</button></footer>
  </div></div>
}
createRoot(document.getElementById('root')).render(<App />)
