const pending = new Map()
let sequence = 0
window.addEventListener('cockpit-reply', event => {
  const {id, result, error} = event.detail
  const job = pending.get(id)
  if (!job) return
  pending.delete(id); clearTimeout(job.timeout)
  if (error) job.reject(new Error(error)); else job.resolve(result)
})
export function invoke(method, params = {}) {
  if (!window.CockpitRuntime) return Promise.reject(new Error('请在车机验证应用中使用本地能力'))
  const id = String(++sequence)
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error('操作尚未确认，请查看任务记录')) }, 45000)
    pending.set(id, {resolve, reject, timeout})
    window.CockpitRuntime.request(JSON.stringify({id, method, params}))
  })
}
