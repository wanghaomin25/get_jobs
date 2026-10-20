'use client'

import { useState, useEffect } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { BiLogOut, BiSave, BiBriefcase, BiPlay, BiStop } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import AnalysisContent from '@/app/zhilian/analysis/AnalysisContent'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'

interface ZhilianConfig {
  id?: number
  keywords?: string
  cityCode?: string
  salary?: string
  jobNature?: string
}

interface Option { name: string; code: string }
interface ZhilianOptions { city: Option[]; jobNature: Option[] }

export default function ZhilianPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  // T3：投递状态以后端为准（IDLE/QUEUED/RUNNING），解决"刷新页面后状态归零"的问题
  // Z4：RUNNING 且后端 stopRequested=true 时映射为 STOPPING 过渡态（按钮显示"停止中…"）
  const [deliverState, setDeliverState] = useState<'IDLE' | 'QUEUED' | 'RUNNING' | 'STOPPING'>('IDLE')
  const [blockedByName, setBlockedByName] = useState('')
  // 兼容既有判断逻辑：RUNNING 才算投递中
  const isDelivering = deliverState === 'RUNNING'
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [backendAvailable, setBackendAvailable] = useState(true)

  const [config, setConfig] = useState<ZhilianConfig>({ keywords: '', cityCode: '', salary: '', jobNature: '' })
  const [options, setOptions] = useState<ZhilianOptions>({ city: [], jobNature: [] })
  const [loadingConfig, setLoadingConfig] = useState(true)

  useEffect(() => {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('[智联招聘] EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API_BASE}/api/jobs/login-status/stream`, {
      onOpen: () => console.log('[智联招聘 SSE] 连接已打开'),
      onError: (e, attempt, delay) => {
        console.warn(`[智联招聘 SSE] 连接错误，第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] connected事件数据:', data)
              console.log('[智联招聘 SSE] zhilianLoggedIn状态:', data.zhilianLoggedIn)
              setIsLoggedIn(data.zhilianLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[智联招聘 SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] login-status事件数据:', data)
              if (data.platform === 'zhilian') {
                console.log('[智联招聘 SSE] 智联登录状态变更:', data.isLoggedIn)
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[智联招聘 SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => client.close()
  }, [])

  // 与猎聘一致的关键词解析/序列化
  const parseKeywordsFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) return arr.filter(Boolean).join(', ')
      } catch (e) {
        console.warn('[智联] 解析关键词JSON失败，使用原值:', e)
      }
    }
    return t.replace(/，/g, ',')
  }

  const serializeKeywordsForDb = (display?: string): string => {
    const raw = (display || '').trim()
    if (!raw) return '[]'
    const norm = raw.replace(/，/g, ',')
    const tokens = norm
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
    return JSON.stringify(tokens)
  }

  const fetchAllData = async () => {
    try {
      const res = await fetch(`${API_BASE}/api/zhilian/config`)
      const data = await res.json()
      if (data.config) {
        const normalized = { ...data.config }
        normalized.keywords = parseKeywordsFromDb(data.config.keywords)
        setConfig(normalized)
      }
      if (data.options) setOptions(data.options)
    } catch (e) {
      console.error('[智联] 获取配置失败:', e)
    } finally {
      setLoadingConfig(false)
    }
  }

  useEffect(() => { fetchAllData() }, [])

  // 探测后端可用性（与 51job 保持一致风格）
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch(`${API_BASE}/api/zhilian/config`, { method: 'GET' })
        const ok = !!res && res.ok
        setBackendAvailable(ok)
        if (ok) {
          await fetchAllData()
        } else {
          setLoadingConfig(false)
        }
      } catch (e) {
        setBackendAvailable(false)
        setLoadingConfig(false)
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // T3：投递状态以后端为准——挂载时查询一次，非 IDLE 时每 3 秒轮询同步
  // Z3：查询失败不再中断轮询链（失败降频 5s 重试），否则停止后状态永远无法同步回 IDLE
  const syncDeliveryStatus = async (): Promise<'IDLE' | 'QUEUED' | 'RUNNING' | 'STOPPING' | null> => {
    try {
      const res = await fetch(`${API_BASE}/api/zhilian/status`)
      if (res.ok) {
        const data = await res.json()
        const st = (data.state as 'IDLE' | 'QUEUED' | 'RUNNING') || (data.isRunning ? 'RUNNING' : 'IDLE')
        // Z4：RUNNING 且停止请求已发出 → 过渡态；字段缺失（旧后端）时 undefined 自然 falsy，向后兼容
        const effective = st === 'RUNNING' && data.stopRequested === true ? 'STOPPING' : st
        setDeliverState(effective)
        setBlockedByName(data.blockedByName || '')
        return effective
      }
    } catch (e) {
      console.warn('[zhilian] 查询投递状态失败（后端可能未启动）:', e)
    }
    return null
  }

  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | null = null
    const loop = async () => {
      if (stopped) return
      const st = await syncDeliveryStatus()
      if (stopped) return
      // Z3：失败（null）也必须重排——否则轮询链一旦断开，停止后的 IDLE 永远不会反映到 UI，
      // 按钮永久卡在"停止投递"。失败降频 5s，成功且非 IDLE 维持 3s，IDLE 停止轮询。
      if (st === null) {
        timer = setTimeout(loop, 5000)
      } else if (st !== 'IDLE') {
        timer = setTimeout(loop, 3000)
      } else {
        // BUGFIX：IDLE 不能永久停轮询——挂载时若为 IDLE，loop 就死在这里，之后"点开始"并不会
        // 重启它；点停止后 UI 停在"停止中..."，后端早已回 IDLE 却永远无人来查。IDLE 用 10s
        // 低频保活（后端本地查询，开销可忽略），保证任意来源的状态变化都能被同步。
        timer = setTimeout(loop, 10000)
      }
    }
    loop()
    return () => {
      stopped = true
      if (timer) clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleStartDelivery = async () => {
    try {
      // 先落库再启动：避免页面上改过配置却没点“保存配置”时，投递仍按旧库值执行（Boss 同款陷阱）。
      // Z6：handleSaveConfig 失败时已自行弹窗提示，这里静默 return 即可。
      const saved = await handleSaveConfig(true)
      if (!saved) return
      const response = await fetch(`${API_BASE}/api/zhilian/start`, { method: 'POST' })
      const data = await response.json().catch(() => ({}))
      // T3：用 /start 返回体里的 state 决定初始显示，不再无条件假定为运行中
      if (data.success) {
        setDeliverState(data.state === 'QUEUED' ? 'QUEUED' : 'RUNNING')
        setBlockedByName(data.blockedByName || '')
      } else {
        // Z6：启动失败（请先登录/任务已在运行中/后端异常）必须让用户看见，不能静默
        setDeliverState('IDLE')
        setSaveResult({ success: false, message: data.message || '启动失败，请稍后重试' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      // Z6：网络异常也要可见
      setDeliverState('IDLE')
      setSaveResult({ success: false, message: `无法连接后端服务（${API_BASE}），请确认服务已启动` })
      setShowSaveDialog(true)
    }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/stop`, { method: 'POST' })
      const data = await response.json().catch(() => ({}))
      if (data.success) {
        // 排队取消立即回 IDLE；运行中由轮询/看门狗收敛（STOPPING 过渡态由轮询同步）
        const st = await syncDeliveryStatus()
        if (!st) setDeliverState('IDLE')
      } else {
        // Z5：400 多为竞态——点击瞬间任务已自然结束。这是正常情况，拉平状态并提示，
        // 不做处理的话叠加轮询中断会让按钮永久卡在"停止投递"
        await syncDeliveryStatus()
        setSaveResult({ success: true, message: data.message || '任务已结束，无需停止' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      // Z5：网络异常时提示，轮询兜底会自行恢复同步
      setSaveResult({ success: false, message: '停止请求发送失败，请稍后查看状态（页面会自动恢复同步）' })
      setShowSaveDialog(true)
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/logout`, { method: 'POST' })
      const data = await response.json()
      setIsLoggedIn(false)
      setLogoutResult({ success: data.success, message: data.success ? '已退出登录，Cookie已清空。' : data.message })
      setShowLogoutResultDialog(true)
    } catch (error) {
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  const handleSaveCookie = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/cookie/save?platform=zhilian`, { method: 'POST' })
      const data = await response.json()
      setSaveResult({ success: data.success, message: data.success ? '配置保存成功。' : data.message })
      setShowSaveDialog(true)
    } catch (error) {
      setSaveResult({ success: false, message: '配置保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  // silent=true 时不弹结果框（供“开始投递”前静默落库用）；返回是否保存成功
  const handleSaveConfig = async (silent: boolean = false): Promise<boolean> => {
    try {
      const payload = { ...config, keywords: serializeKeywordsForDb(config.keywords) }
      const response = await fetch(`${API_BASE}/api/zhilian/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (response.ok) {
        try { await fetch(`${API_BASE}/api/cookie/save?platform=zhilian`, { method: 'POST' }) } catch {}
        await fetchAllData()
        if (!silent) {
          setSaveResult({ success: true, message: '保存成功，配置已更新。' })
          setShowSaveDialog(true)
        }
        return true
      }
      // 失败一律提示，避免用户以为配置已经生效
      setSaveResult({ success: false, message: '保存失败：后端返回异常状态。' })
      setShowSaveDialog(true)
      return false
    } catch (error) {
      console.error('[智联] 保存配置失败:', error)
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
      return false
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="智联招聘配置"
        subtitle="配置智联招聘平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-purple-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 请先登录智联招聘
              </Button>
            ) : deliverState === 'QUEUED' ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-amber-500 to-orange-600 hover:from-amber-600 hover:to-orange-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiStop className="mr-1" /> 取消排队
              </Button>
            ) : deliverState === 'STOPPING' ? (
              // Z4：停止请求已发出、看门狗宽限期内（最长 15s）——明确告知而非让用户以为没生效
              <Button size="sm" disabled className="rounded-full bg-gradient-to-r from-gray-400 to-slate-500 text-white px-4 shadow cursor-not-allowed">
                <BiStop className="mr-1 animate-pulse" /> 停止中...
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiStop className="mr-1" /> 停止投递
              </Button>
            ) : (
              <Button onClick={handleStartDelivery} size="sm" className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiPlay className="mr-1" /> 开始投递
              </Button>
            )}
            {deliverState === 'QUEUED' && (
              <span className="text-xs text-amber-600 dark:text-amber-400 whitespace-nowrap max-w-[220px] truncate" title={`${blockedByName || '其他平台'} 投递中，本任务已排队等待`}>
                {blockedByName || '其他平台'} 投递中，已排队等待
              </span>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiLogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={() => handleSaveConfig()} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiSave className="mr-1" /> 保存配置
            </Button>
          </div>
        }
      />

      <Tabs defaultValue="config" className="w-full">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="config">平台配置</TabsTrigger>
          <TabsTrigger value="analytics">投递分析</TabsTrigger>
        </TabsList>

        <TabsContent value="config" className="space-y-6 mt-6">
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                智联招聘平台说明
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <p className="text-sm text-muted-foreground">请在浏览器标签页中登录智联招聘平台，登录成功后系统会自动检测登录状态。</p>
                <p className="text-sm text-muted-foreground">登录成功后，点击"开始投递"按钮启动自动投递任务。</p>
                <p className="text-sm text-muted-foreground">点击"保存配置"按钮可手动保存当前登录相关信息到数据库。</p>
              </div>
            </CardContent>
          </Card>

          {/* 配置表单 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                配置参数
              </CardTitle>
            </CardHeader>
            <CardContent>
              {loadingConfig ? (
                <p className="text-sm text-muted-foreground">配置加载中...</p>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>搜索关键词（逗号分隔）</Label>
                    <Input
                      placeholder="如：Java, 后端, Spring"
                      value={config.keywords || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>城市</Label>
                    <Select
                      value={config.cityCode || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, cityCode: e.target.value }))}
                      placeholder="请选择城市"
                    >
                      {options.city.map((o) => (
                        <option key={o.code} value={o.code}>{o.name}</option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>工作性质</Label>
                    <Select
                      value={config.jobNature || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, jobNature: e.target.value }))}
                      placeholder="请选择工作性质"
                    >
                      {options.jobNature.map((o) => (
                        <option key={o.name} value={o.code}>{o.name}</option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>薪资范围（最低和最高工资，用逗号分割）</Label>
                    <Input
                      placeholder="如：12000, 20000 或 不限"
                      value={config.salary || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, salary: e.target.value }))}
                    />
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="analytics" className="space-y-6 mt-6">
          <AnalysisContent />
        </TabsContent>
      </Tabs>

      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiLogOut className="text-red-500" /> 确认退出登录
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setShowLogoutDialog(false)} className="rounded-full px-4">取消</Button>
                <Button onClick={async () => { await triggerLogout(); setShowLogoutDialog(false) }} className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 text-white px-4">确认退出</Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiLogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                {logoutResult.success ? '退出登录成功' : '退出登录失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
              <Button onClick={() => setShowLogoutResultDialog(false)} className={`rounded-full px-4 ${logoutResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 保存Cookie结果弹框 */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                {saveResult.success ? '保存成功' : '保存失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
              <Button onClick={() => setShowSaveDialog(false)} className={`rounded-full px-4 ${saveResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}
