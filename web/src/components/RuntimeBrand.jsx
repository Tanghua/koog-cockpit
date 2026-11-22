export default function RuntimeBrand({ name = 'Agent', compact = false }) {
  const kind = name === 'Koog' ? 'koog' : 'agent-runtime'
  return <span className={`runtime-brand ${kind} ${compact ? 'compact' : ''}`} data-runtime-brand={name}>
    <span className="runtime-brand-mark" aria-hidden="true">{name === 'Koog' ? 'K' : 'AR'}</span>
    <span>{name} 智能座舱</span>
  </span>
}
