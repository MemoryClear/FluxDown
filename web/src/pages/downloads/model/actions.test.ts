import { describe, expect, test } from 'bun:test'
import { canOpenLocally, canRevealLocally } from './actions'
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

describe('canRevealLocally', () => {
  test('本地任务不论状态和文件状态均可定位', () => {
    expect(canRevealLocally(view({}))).toBe(true)
    expect(canRevealLocally(view({ state: 'downloading' }))).toBe(true)
    expect(canRevealLocally(view({ state: 'failed' }))).toBe(true)
    expect(canRevealLocally(view({ fileMissing: true }))).toBe(true)
  })

  test('远程任务不可在宿主机定位', () => {
    expect(canRevealLocally(view({ source: 'remote' }))).toBe(false)
  })
})
