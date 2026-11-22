import {useEffect,useRef,useState} from 'react'
import {nativeCall,NativeSocket,onNative} from './native'
import {invoke} from './bridge'
const schema=properties=>({type:'object',properties,additionalProperties:false})
const tools=[
 {name:'local_media_control',description:'通过本车 Koog/Agent Runtime 操作真实 MediaX 媒体。所有播放、暂停、上下曲和当前歌曲查询必须用此工具，不用模拟音乐工具。play仅恢复当前音源，不能搜歌；仅依据返回的真实歌曲信息回答，不编造歌名。暂停状态仍可有当前曲目，查询时请报告曲名并说明已暂停。',inputSchema:{...schema({action:{type:'string',enum:['play','pause','next','previous','status']}}),required:['action']}},
 {name:'local_navigation_search',description:'通过本车运行时请求高德搜索目的地，显示候选地点。导航到某地先用此工具，不能声称已开始导航，不用模拟地图工具。',inputSchema:{...schema({destination:{type:'string',maxLength:100}}),required:['destination']}},
 {name:'local_map_open',description:'通过本车运行时打开真实高德主页，无目的地时使用。',inputSchema:schema({})},
 {name:'local_aircon_open',description:'通过本车运行时打开真实空调面板，尚不支持修改温度，请明确说明需手动操作。',inputSchema:schema({})},
]
export function useRuntimeVoice({onMedia,onNavigation}) {
 const [voice,setVoice]=useState({muted:true,state:'idle',status:'点击麦克风，开始语音对话',inputLevel:0,messages:[]})
 const session=useRef(null),callbacks=useRef({onMedia,onNavigation})
 callbacks.current={onMedia,onNavigation}
 const update=value=>setVoice(old=>({...old,...value}))
 function stop(status='语音已关闭') {
  const current=session.current;session.current=null
  if(current){clearTimeout(current.timeout);current.socket.close()}
  nativeCall('voiceStop').catch(()=>{})
  update({muted:true,state:'idle',inputLevel:0,status})
 }
 async function toggle() {
  if(session.current){stop();return}
  const current={socket:null,seen:new Map()};session.current=current
  update({muted:false,state:'connecting',error:null,status:'正在连接语音服务…'})
  const fail=message=>{if(session.current===current){stop(message);update({error:message,state:'error'})}}
  const socket=new NativeSocket('/api/realtime?sessionId=runtime-'+crypto.randomUUID());current.socket=socket
  const send=event=>{if(session.current===current&&socket.readyState===1)socket.send(JSON.stringify({event_id:crypto.randomUUID(),...event}))}
  current.timeout=setTimeout(()=>fail('语音连接超时，请检查网络后重新开启'),25000)
  socket.addEventListener('open',()=>send({type:'session.hello',protocol:{min:'7.0.0',max:'7.0.0'},client:{type:'mobile',version:'0.3.0',instance_id:'runtime-'+crypto.randomUUID(),label:'Local Runtime Cockpit'},capabilities:['input.audio','input.text','playback.receipts','client.tools','session.heartbeat','session.takeover'],tools,locale:'zh-CN',time_zone:'Asia/Shanghai',connection:{voice_enabled:true,input_enabled:true,output_enabled:true,text_only:false,wake_word_only:false,takeover:true}}))
  socket.addEventListener('close',()=>{if(session.current===current)fail('语音连接已断开，请重新开启')})
  socket.addEventListener('error',()=>fail('无法连接语音服务，请检查网络或访问凭据'))
  socket.addEventListener('message',async message=>{
   if(session.current!==current)return
   let event;try{event=JSON.parse(message.data)}catch{return}
   // The gateway initiates heartbeat pings; clients only return correlated pongs.
   if(event.type==='session.ping')send({type:'session.pong',request_event_id:event.event_id})
   if(event.type==='voice.ready'&&!current.recording){
    current.recording=true;clearTimeout(current.timeout)
    try{await nativeCall('voiceStart');if(session.current===current)update({state:'listening',status:'正在聆听，说完后稍作停顿'})}catch(error){fail(error.message)}
   }
   if(event.type==='voice.state')update({state:event.state==='processing'?'thinking':event.state||'listening'})
   if(event.type==='error'){
    const message=event.message||event.error?.message||'语音服务返回错误'
    if(/manual response is already in progress|response (?:is )?already in progress/i.test(message)){
     update({state:'thinking',status:'检测到语音重叠，请稍作停顿再说'});return
    }
    fail(message);return
   }
   if(event.type==='transcript.final'||event.type==='transcript.delta'){
    const role=event.role||event.speaker||'assistant',text=event.content||event.text||event.transcript||event.delta||''
    if(text)setVoice(old=>{const key=event.itemId||event.responseId||event.turnId||role;const messages=[...old.messages];const last=messages.at(-1);if(last?.key===key)messages[messages.length-1]={...last,text:event.type.endsWith('delta')&&!event.replace?last.text+text:text};else messages.push({key,role,text});return {...old,messages:messages.slice(-6),status:text}})
   }
   if(event.type==='client.action.request'){
    if(current.seen.has(event.event_id)){send(await current.seen.get(event.event_id));return}
    const operation=(async()=>{
     try{
      const args=event.arguments||{};let action,arguments_={}
      if(event.name==='client.tool.local_media_control'){
       action={play:'media_play',pause:'media_pause',next:'media_next',previous:'media_previous',status:'media_status'}[args.action]
       if(!action)throw Error('不支持此媒体操作')
       callbacks.current.onMedia?.()
      }else if(event.name==='client.tool.local_navigation_search'){action='navigationCandidates';arguments_={destination:args.destination};callbacks.current.onNavigation?.()}
      else if(event.name==='client.tool.local_map_open')action='map'
      else if(event.name==='client.tool.local_aircon_open')action='aircon'
      else throw Error('客户端未开放该操作')
      update({state:'thinking',status:'正在由本地运行时执行…'})
      const result=await invoke('voiceLocal',{action,arguments:arguments_})
      return {type:'client.action.result',request_event_id:event.event_id,status:'completed',output:{...result,currentTrack:result.media?.title||'',playbackState:result.media?.playing?'playing':'paused',instructions:'仅依据实际回执回答。accepted表示请求已发送，不能宣称已成功。歌名为空就不报歌名。导航搜索不是开始导航。'}}
     }catch(error){return {type:'client.action.result',request_event_id:event.event_id,status:'failed',error:{code:'local_runtime_failed',message:error.message.slice(0,500)}}}
    })();current.seen.set(event.event_id,operation);send(await operation)
   }
  })
 }
 useEffect(()=>{
  const off=onNative((type,data)=>{
   if(type==='level'&&session.current)update({inputLevel:data.input||0})
   if(type==='voiceOff'&&session.current)stop(data.reason==='background'?'应用已进入后台，语音已停止':'语音已停止，请重新开启')
  })
  return ()=>{off();stop()}
 },[])
 return {...voice,toggle}
}
