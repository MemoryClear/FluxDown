// 安全与访问：local_server_* 配置组 + 令牌管理 + WS 会话状态。
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Eye, EyeOff, RefreshCw } from 'lucide-react'
import { api } from '../../lib/api'
import { CopyButton } from '../CopyButton'
import { useI18n } from '../../lib/i18n'
import type { ConfigMap } from '../../lib/types'
import { connStore, useStore } from '../../lib/ws'
import { alertDialog } from '../../lib/confirm'
import { SetRow, SetSwitch, TextInput } from './controls'

export function SecuritySettings({
  config,
  mutate,
}: {
  config: ConfigMap
  mutate: (entries: ConfigMap) => void
}) {
  const { t } = useI18n()
  const token = config.local_server_token ?? ''
  const [showToken, setShowToken] = useState(false)
  const takeover = (config.local_server_takeover_enabled ?? 'true') === 'true'
  const jsonrpc = (config.local_server_jsonrpc_enabled ?? 'true') === 'true'
  const conn = useStore(connStore)
  const { data: stats } = useQuery({ queryKey: ['stats'], queryFn: api.stats, refetchInterval: 5000 })

  function saveToken(next: string) {
    const v = next.trim()
    if (v === token) return
    mutate({ local_server_token: v })
    void alertDialog({ message: t('set.sec.tokenSaved') })
  }

  function randomToken() {
    const bytes = new Uint8Array(16)
    crypto.getRandomValues(bytes)
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    saveToken(`fxd_${hex}`)
  }

  return (
    <>
      <h2 className="set-title">{t('set.security')}</h2>
      <p className="set-desc">{t('set.sec.desc')}</p>
      <div className="set-group">
        <SetRow title={t('set.sec.token')} desc={t('set.sec.tokenDesc')}>
          <div className="token-box">
            <TextInput value={token} onCommit={saveToken} password={!showToken} placeholder={t('set.sec.tokenPlaceholder')} />
            <button
              type="button"
              title={showToken ? t('set.sec.hideToken') : t('set.sec.showToken')}
              onClick={() => setShowToken((s) => !s)}
            >
              {showToken ? <EyeOff /> : <Eye />}
            </button>
            <CopyButton value={token} title={t('set.sec.copyToken')} />
            <button type="button" title={t('set.sec.genToken')} onClick={randomToken}>
              <RefreshCw />
            </button>
          </div>
        </SetRow>
      </div>
      <div className="set-group">
        <SetRow title={t('set.sec.jsonrpc')} desc={t('set.sec.jsonrpcDesc')}>
          <SetSwitch checked={jsonrpc} onCheckedChange={(v) => mutate({ local_server_jsonrpc_enabled: String(v) })} />
        </SetRow>
        <SetRow title={t('set.sec.takeover')} desc={t('set.sec.takeoverDesc')}>
          <SetSwitch checked={takeover} onCheckedChange={(v) => mutate({ local_server_takeover_enabled: String(v) })} />
        </SetRow>
      </div>
      <div className="set-group">
        <SetRow
          title={t('set.sec.ws')}
          desc={conn.status === 'connected' ? t('set.sec.wsConnected', { rtt: conn.rttMs ?? '—' }) : t('set.sec.wsDisconnected')}
        >
          <span className="set-value">{stats ? t('set.sec.wsSessions', { n: stats.wsClients }) : '—'}</span>
        </SetRow>
      </div>
    </>
  )
}
