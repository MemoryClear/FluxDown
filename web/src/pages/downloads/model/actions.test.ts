import { describe, expect, test } from 'bun:test'
import { canOpenLocally } from './actions'
import type { DownloadTaskView } from './task'

function view(overrides: Partial<DownloadTaskView>): DownloadTaskView {
  return { source: 'local', state: 'completed', fileMissing: false, ...overrides } as unknown as DownloadTaskView
}

describe('canOpenLocally', () => {
  test('本地已完成且文件存在 → 可打开', () => {
    expect(canOpenLocally(view({}))).toBe(true)
  })

  test('远程任务不可在宿主机打开', () => {
    expect(canOpenLocally(view({ source: 'remote' }))).toBe(false)
  })

  test('未完成 / 文件缺失不可打开', () => {
    expect(canOpenLocally(view({ state: 'downloading' }))).toBe(false)
    expect(canOpenLocally(view({ state: 'failed' }))).toBe(false)
    expect(canOpenLocally(view({ fileMissing: true }))).toBe(false)
  })
})
