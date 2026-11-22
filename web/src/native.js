export const native = typeof window !== 'undefined' && Boolean(window.AndroidCockpit)
let sequence = 0
const pending = new Map()
const listeners = new Set()
export function nativeCall(method, payload = {}) {
  return new Promise((resolve, reject) => {
    const id = `call_${++sequence}`
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('原生操作超时')) }, method === 'voiceStart' ? 120000 : 40000)
    pending.set(id, { resolve, reject, timer })
    try { window.AndroidCockpit.call(id, method, JSON.stringify(payload)) }
    catch (error) { clearTimeout(timer); pending.delete(id); reject(error) }
  })
}
export function onNative(listener) { listeners.add(listener); return () => listeners.delete(listener) }
if (native) {
  window.__qwaNativeEvent = raw => {
    const { type, data } = JSON.parse(raw)
    if (type === 'result') {
      const p = pending.get(data.id)
      if (!p) return
      clearTimeout(p.timer); pending.delete(data.id)
      if (data.error) p.reject(new Error(data.error)); else p.resolve(data.value)
    } else {
      for (const listener of listeners) listener(type, data)
      if (type === 'voiceOff') window.dispatchEvent(new CustomEvent('qwa-voice-off', { detail: data }))
    }
  }
}
function apiPath(value) {
  const url = new URL(typeof value === 'string' ? value : value.url, window.location.origin)
  return `${url.pathname}${url.search}`
}
export class NativeSocket extends EventTarget {
  readyState = 0
  bufferedAmount = 0
  constructor(url) {
    super()
    this.id = `socket_${++sequence}`
    this.unsubscribe = onNative((type, data) => {
      if (type !== 'socket' || data.id !== this.id) return
      if (data.event === 'open') { this.readyState = 1; this.dispatchEvent(new Event('open')) }
      if (data.event === 'message') this.dispatchEvent(new MessageEvent('message', { data: data.text }))
      if (data.event === 'error') this.dispatchEvent(new Event('error'))
      if (data.event === 'close') {
        this.readyState = 3; this.unsubscribe()
        this.dispatchEvent(new CloseEvent('close', { code: data.code, reason: data.reason }))
      }
    })
    nativeCall('socketOpen', { socketId: this.id, path: apiPath(url) }).catch(() => {
      this.readyState = 3; this.unsubscribe(); this.dispatchEvent(new Event('error'))
      this.dispatchEvent(new CloseEvent('close', { code: 1006 }))
    })
  }
  send(text) { if (this.readyState !== 1) throw new Error('对话连接未就绪'); nativeCall('socketSend', { socketId: this.id, text }).catch(() => this.dispatchEvent(new Event('error'))) }
  close() { this.readyState = 3; this.unsubscribe(); nativeCall('socketClose', { socketId: this.id }).catch(() => {}) }
}
