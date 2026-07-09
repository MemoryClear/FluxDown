// 新建下载对话框（对齐 design/web #dlg-new）—— 多行 URL 逐条创建任务；保存目录默认取自
// 服务器配置（['config'] 的 default_save_dir），支持 FsPicker 浏览服务器目录；高级选项
// （Cookies/Referrer/单任务代理/Checksum）为可折叠面板，行为对齐原型 #advToggle。

import { useEffect, useMemo, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import * as Select from '@radix-ui/react-select'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronDown, ChevronRight, X } from 'lucide-react'
import { api } from '../../lib/api'
import { cn } from '../../lib/cn'
import { newDownloadOpenStore } from '../../lib/dialogs'
import { useI18n } from '../../lib/i18n'
import { useStore } from '../../lib/ws'
import { FsPicker } from './fs-picker'

/** Radix Select 不允许 Item 的 value 为空字符串，用哨兵值代表"未设置/默认"语义。 */
const EMPTY_VALUE = '__default__'

function SelectField({
  value,
  onChange,
  options,
  ariaLabel,
}: {
  value: string
  onChange: (v: string) => void
  options: { value: string; label: string }[]
  ariaLabel: string
}) {
  return (
    <Select.Root value={value === '' ? EMPTY_VALUE : value} onValueChange={(v) => onChange(v === EMPTY_VALUE ? '' : v)}>
      <Select.Trigger className="select w-full" aria-label={ariaLabel}>
        <Select.Value className="min-w-0 flex-1 truncate text-left" />
        <Select.Icon className="shrink-0 text-text3">
          <ChevronDown size={14} />
        </Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Content
          position="popper"
          sideOffset={6}
          className="z-50 overflow-hidden rounded-lg border border-line bg-surface"
          style={{ minWidth: 'var(--radix-select-trigger-width)', boxShadow: 'var(--shadow)' }}
        >
          <Select.Viewport className="max-h-64 p-1">
            {options.map((o) => (
              <Select.Item
                key={o.value || EMPTY_VALUE}
                value={o.value === '' ? EMPTY_VALUE : o.value}
                className="flex cursor-pointer select-none items-center justify-between gap-3 rounded-md px-2.5 py-1.5 text-[13px] text-text outline-none data-[highlighted]:bg-surface2 data-[state=checked]:text-accent"
              >
                <Select.ItemText>{o.label}</Select.ItemText>
                <Select.ItemIndicator>
                  <Check size={13} />
                </Select.ItemIndicator>
              </Select.Item>
            ))}
          </Select.Viewport>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  )
}

interface FormState {
  urls: string
  fileName: string
  segments: string
  saveDir: string
  saveDirTouched: boolean
  queueId: string
  userAgent: string
  cookies: string
  referrer: string
  proxyUrl: string
  checksum: string
  advOpen: boolean
}

function emptyForm(saveDir = ''): FormState {
  return {
    urls: '',
    fileName: '',
    segments: '0',
    saveDir,
    saveDirTouched: false,
    queueId: '',
    userAgent: '',
    cookies: '',
    referrer: '',
    proxyUrl: '',
    checksum: '',
    advOpen: false,
  }
}

export function NewDownloadDialog() {
  const open = useStore(newDownloadOpenStore)
  const queryClient = useQueryClient()
  const [form, setForm] = useState<FormState>(() => emptyForm())
  const [lineErrors, setLineErrors] = useState<Record<number, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const { t } = useI18n()
  const segmentOptions = [
    { value: '0', label: t('newDl.segmentsAuto') },
    { value: '1', label: t('newDl.segmentsN', { n: 1 }) },
    { value: '2', label: t('newDl.segmentsN', { n: 2 }) },
    { value: '4', label: t('newDl.segmentsN', { n: 4 }) },
    { value: '8', label: t('newDl.segmentsN', { n: 8 }) },
    { value: '16', label: t('newDl.segmentsN', { n: 16 }) },
    { value: '32', label: t('newDl.segmentsN', { n: 32 }) },
  ]
  const userAgentOptions = [
    { value: '', label: t('newDl.globalDefault') },
    {
      value:
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
      label: 'Chrome',
    },
    {
      value: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0',
      label: 'Firefox',
    },
    {
      value:
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0',
      label: 'Edge',
    },
    {
      value:
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15',
      label: 'Safari',
    },
  ]

  const { data: config } = useQuery({ queryKey: ['config'], queryFn: api.getConfig, enabled: open })
  const { data: queues } = useQuery({ queryKey: ['queues'], queryFn: api.listQueues, enabled: open })
  const { data: stats } = useQuery({ queryKey: ['stats'], queryFn: api.stats, enabled: open })
  const demoMode = stats?.demoMode ?? false
  const demoUrl = stats?.demoUrl ?? ''

  // 每次打开都是一张新表单。
  useEffect(() => {
    if (open) {
      setForm(emptyForm())
      setLineErrors({})
    }
  }, [open])

  // 演示模式：URL 锁定为服务器指定的演示文件（服务端 demo_guard 同样强制校验，
  // 这里只是避免用户输入注定被拒绝的链接）。
  useEffect(() => {
    if (open && demoMode && demoUrl) {
      setForm((f) => (f.urls === demoUrl ? f : { ...f, urls: demoUrl }))
      setLineErrors({})
    }
  }, [open, demoMode, demoUrl])

  // 保存目录默认值来自服务器配置；一旦用户手动改过就不再被配置覆盖。
  useEffect(() => {
    const dir = config?.default_save_dir
    if (open && !form.saveDirTouched && dir) {
      setForm((f) => ({ ...f, saveDir: dir }))
    }
  }, [open, config, form.saveDirTouched])

  const urlLines = useMemo(
    () =>
      form.urls
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean),
    [form.urls],
  )
  const isSingle = urlLines.length <= 1

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  function close() {
    newDownloadOpenStore.set(false)
  }

  async function handleSubmit() {
    if (urlLines.length === 0 || submitting) return
    setSubmitting(true)
    const nextErrors: Record<number, string> = {}
    let anyOk = false
    for (let i = 0; i < urlLines.length; i++) {
      try {
        await api.createTask({
          url: urlLines[i],
          fileName: isSingle ? form.fileName.trim() || undefined : undefined,
          saveDir: form.saveDir.trim() || undefined,
          segments: Number(form.segments),
          cookies: form.cookies.trim() || undefined,
          referrer: form.referrer.trim() || undefined,
          proxyUrl: form.proxyUrl.trim() || undefined,
          userAgent: form.userAgent || undefined,
          queueId: form.queueId || undefined,
          checksum: form.checksum.trim() || undefined,
        })
        anyOk = true
      } catch (err) {
        nextErrors[i] = err instanceof Error ? err.message : String(err)
      }
    }
    setSubmitting(false)
    setLineErrors(nextErrors)
    if (anyOk) void queryClient.invalidateQueries({ queryKey: ['tasks'] })
    if (Object.keys(nextErrors).length === 0) close()
  }

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="wbackdrop show" />
        <Dialog.Content asChild>
          <form
            className="dialog show"
            onSubmit={(e) => {
              e.preventDefault()
              void handleSubmit()
            }}
          >
            <header className="dlg-head">
              <Dialog.Title asChild>
                <b>{t('newDl.title')}</b>
              </Dialog.Title>
              <Dialog.Close asChild>
                <button type="button" className="icon-btn sm" aria-label={t('common.close')}>
                  <X size={16} />
                </button>
              </Dialog.Close>
            </header>
            <Dialog.Description className="sr-only">{t('newDl.desc')}</Dialog.Description>
            <div className="dlg-body">
              <label className="field-label" htmlFor="nd-urls">
                {demoMode ? t('newDl.urlLabelDemo') : t('newDl.urlLabel')}
              </label>
              <textarea
                id="nd-urls"
                className="text-input area"
                rows={3}
                spellCheck={false}
                readOnly={demoMode}
                aria-readonly={demoMode}
                value={form.urls}
                onChange={(e) => {
                  if (demoMode) return
                  set('urls', e.target.value)
                  setLineErrors({})
                }}
              />
              {demoMode && (
                <p className="mt-1 text-xs break-all text-text3">
                  {t('newDl.demoHint')}
                </p>
              )}
              <div className="grid2">
                <div>
                  <label className="field-label" htmlFor="nd-filename">
                    {t('newDl.fileName')}
                  </label>
                  <input
                    id="nd-filename"
                    className="text-input"
                    type="text"
                    placeholder={t('newDl.fileNamePlaceholder')}
                    disabled={!isSingle}
                    value={form.fileName}
                    onChange={(e) => set('fileName', e.target.value)}
                  />
                </div>
                <div>
                  <label className="field-label">{t('newDl.segments')}</label>
                  <SelectField value={form.segments} onChange={(v) => set('segments', v)} options={segmentOptions} ariaLabel={t('newDl.segments')} />
                </div>
              </div>
              <label className="field-label" htmlFor="nd-savedir">
                {t('newDl.saveDir')}
              </label>
              <div className="dir-row">
                <input
                  id="nd-savedir"
                  className="text-input"
                  type="text"
                  spellCheck={false}
                  value={form.saveDir}
                  onChange={(e) => setForm((f) => ({ ...f, saveDir: e.target.value, saveDirTouched: true }))}
                />
                <FsPicker value={form.saveDir} onChange={(p) => setForm((f) => ({ ...f, saveDir: p, saveDirTouched: true }))} />
              </div>
              <div className="grid2">
                <div>
                  <label className="field-label">{t('newDl.queue')}</label>
                  <SelectField
                    value={form.queueId}
                    onChange={(v) => set('queueId', v)}
                    options={[{ value: '', label: t('newDl.defaultQueue') }, ...(queues ?? []).map((q) => ({ value: q.queueId, label: q.name }))]}
                    ariaLabel={t('newDl.queue')}
                  />
                </div>
                <div>
                  <label className="field-label">{t('newDl.userAgent')}</label>
                  <SelectField value={form.userAgent} onChange={(v) => set('userAgent', v)} options={userAgentOptions} ariaLabel={t('newDl.userAgent')} />
                </div>
              </div>
              <button type="button" className={cn('adv-toggle', form.advOpen && 'open')} onClick={() => set('advOpen', !form.advOpen)}>
                <ChevronRight size={13} />
                {t('newDl.advanced')}
              </button>
              <div className={cn('adv-panel', form.advOpen && 'open')}>
                <label className="field-label" htmlFor="nd-cookies">
                  {t('newDl.cookies')}
                </label>
                <input
                  id="nd-cookies"
                  className="text-input"
                  type="text"
                  placeholder="key=value; key2=value2"
                  value={form.cookies}
                  onChange={(e) => set('cookies', e.target.value)}
                />
                <div className="grid2">
                  <div>
                    <label className="field-label" htmlFor="nd-referrer">
                      {t('newDl.referrer')}
                    </label>
                    <input
                      id="nd-referrer"
                      className="text-input"
                      type="text"
                      placeholder="https://…"
                      value={form.referrer}
                      onChange={(e) => set('referrer', e.target.value)}
                    />
                  </div>
                  <div>
                    <label className="field-label" htmlFor="nd-proxy">
                      {t('newDl.proxy')}
                    </label>
                    <input
                      id="nd-proxy"
                      className="text-input"
                      type="text"
                      placeholder="socks5://127.0.0.1:1080"
                      value={form.proxyUrl}
                      onChange={(e) => set('proxyUrl', e.target.value)}
                    />
                  </div>
                </div>
                <label className="field-label" htmlFor="nd-checksum">
                  {t('newDl.checksum')}
                </label>
                <input
                  id="nd-checksum"
                  className="text-input"
                  type="text"
                  placeholder={t('newDl.checksumPlaceholder')}
                  value={form.checksum}
                  onChange={(e) => set('checksum', e.target.value)}
                />
              </div>
              {Object.keys(lineErrors).length > 0 && (
                <div className="mt-3 flex flex-col gap-1">
                  {urlLines.map(
                    (line, i) =>
                      lineErrors[i] && (
                        <p key={i} className="text-xs break-all text-danger">
                          {t('newDl.lineError', { n: i + 1, line, error: lineErrors[i] })}
                        </p>
                      ),
                  )}
                </div>
              )}
            </div>
            <footer className="dlg-foot">
              <Dialog.Close asChild>
                <button type="button" className="btn ghost">
                  {t('common.cancel')}
                </button>
              </Dialog.Close>
              <button type="submit" className="btn primary" disabled={urlLines.length === 0 || submitting}>
                {submitting ? t('newDl.creating') : t('newDl.create')}
              </button>
            </footer>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
