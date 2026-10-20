'use client'

import { useState, useEffect } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { BiSearch, BiSave, BiTargetLock, BiMap, BiMoney, BiTime, BiBookmark, BiBarChart, BiPlay, BiStop, BiLogOut, BiBriefcase } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import AnalysisContent from '@/app/liepin/analysis/AnalysisContent'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'

interface LiepinConfig {
  id?: number
  keywords?: string
  city?: string
  salaryCode?: string
}

interface LiepinOption {
  id: number
  type: string
  name: string
  code: string
}

interface LiepinOptions {
  city: LiepinOption[]
}

export default function LiepinPage() {
  const [config, setConfig] = useState<LiepinConfig>({
    keywords: '',
    city: '',
    salaryCode: '',
  })
  const [options, setOptions] = useState<LiepinOptions>({
    city: [],
  })
  const [loading, setLoading] = useState(true)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [isCustomCity, setIsCustomCity] = useState(false) // 是否手动输入城市
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  // T3：投递状态以后端为准（IDLE/QUEUED/RUNNING），解决"刷新页面后状态归零"的问题
  // Z4/B：RUNNING 且后端 stopRequested=true 时映射为 STOPPING 过渡态（按钮显示"停止中…"）
  const [deliverState, setDeliverState] = useState<'IDLE' | 'QUEUED' | 'RUNNING' | 'STOPPING'>('IDLE')
  const [blockedByName, setBlockedByName] = useState('')
  // 兼容既有判断逻辑：RUNNING 才算投递中
  const isDelivering = deliverState === 'RUNNING'
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)

  useEffect(() => {
    fetchAllData()

    // 确保在客户端环境且 EventSource 可用
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API_BASE}/api/jobs/login-status/stream`, {
      onOpen: () => {
        console.log('[SSE] 连接已打开')
      },
      onError: (e, attempt, delay) => {
        console.warn(`[SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setIsLoggedIn(data.liepinLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              if (data.platform === 'liepin') {
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => {
      client.close()
    }
  }, [])

  // T3：投递状态以后端为准——挂载时查询一次，非 IDLE 时每 3 秒轮询同步
  // Z3/A：查询失败不再中断轮询链（失败降频 5s 重试），否则停止后状态永远无法同步回 IDLE
  const syncDeliveryStatus = async (): Promise<'IDLE' | 'QUEUED' | 'RUNNING' | 'STOPPING' | null> => {
    try {
      const res = await fetch(`${API_BASE}/api/liepin/status`)
      if (res.ok) {
        const data = await res.json()
        const st = (data.state as 'IDLE' | 'QUEUED' | 'RUNNING') || (data.isRunning ? 'RUNNING' : 'IDLE')
        // Z4/B：RUNNING 且停止请求已发出 → 过渡态；字段缺失（旧后端）时 undefined 自然 falsy，向后兼容
        const effective = st === 'RUNNING' && data.stopRequested === true ? 'STOPPING' : st
        setDeliverState(effective)
        setBlockedByName(data.blockedByName || '')
        return effective
      }
    } catch (e) {
      console.warn('[liepin] 查询投递状态失败（后端可能未启动）:', e)
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
      // Z3/A：失败（null）也必须重排——否则轮询链一旦断开，停止后的 IDLE 永远不会反映到 UI，
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

  // 将数据库中的 JSON 数组字符串转换为逗号分隔的可读字符串
  const parseKeywordsFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) return arr.filter(Boolean).join(', ')
      } catch (e) {
        console.warn('解析关键词JSON失败，使用原值:', e)
      }
    }
    // 兼容逗号或中文逗号分隔的原始字符串
    return t.replace(/，/g, ',')
  }

  // 将输入的逗号分隔字符串转换为 JSON 数组字符串保存到数据库
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
      const response = await fetch(`${API_BASE}/api/liepin/config`)
      const data = await response.json()

      console.log('Fetched liepin data:', data)

      if (data.config) {
        const normalized = { ...data.config }
        normalized.keywords = parseKeywordsFromDb(data.config.keywords)
        setConfig(normalized)
        // 检查当前城市是否在选项列表中
        if (data.options?.city && data.config.city) {
          const cityExists = data.options.city.some((c: LiepinOption) => c.name === data.config.city || c.code === data.config.city)
          setIsCustomCity(!cityExists)
        }
      }
      if (data.options) {
        setOptions(data.options)
      }
    } catch (error) {
      console.error('Failed to fetch liepin data:', error)
    } finally {
      setLoading(false)
    }
  }

  // silent=true 时不弹结果框（供"开始投递"前静默落库用）；返回是否保存成功
  const handleSave = async (silent: boolean = false): Promise<boolean> => {
    try {
      const payload = { ...config, keywords: serializeKeywordsForDb(config.keywords) }
      const response = await fetch(`${API_BASE}/api/liepin/config`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      })

      if (response.ok) {
        // 统一保存 Cookie（Liepin）
        try {
          await fetch(`${API_BASE}/api/cookie/save?platform=liepin`, { method: 'POST' })
        } catch (e) {
          console.warn('保存 Cookie 失败（Liepin）:', e)
        }

        fetchAllData()
        if (!silent) {
          setSaveResult({ success: true, message: '保存成功，配置与Cookie已更新。' })
          setShowSaveDialog(true)
        }
        return true
      } else {
        // 保存失败：结果也要记录（静默模式下由启动流程负责弹框展示）
        console.warn('保存失败：后端返回非 2xx 状态')
        setSaveResult({ success: false, message: '保存失败：后端返回异常状态。' })
        if (!silent) {
          setShowSaveDialog(true)
        }
        return false
      }
    } catch (error) {
      console.error('Failed to save config:', error)
      // 保存失败：结果也要记录（静默模式下由启动流程负责弹框展示）
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      if (!silent) {
        setShowSaveDialog(true)
      }
      return false
    }
  }

  const handleStartDelivery = async () => {
    try {
      // C：先落库再启动——避免页面上改过配置却没点"保存配置"时，投递仍按旧库值执行
      // （liepin_config 曾为 0 行，后端直接报"关键词为空"）；保存失败时弹框展示并中止启动。
      const saved = await handleSave(true)
      if (!saved) {
        setShowSaveDialog(true)
        return
      }
      const response = await fetch(`${API_BASE}/api/liepin/start`, {
        method: 'POST',
      })
      const data = await response.json().catch(() => ({}))

      if (data.success) {
        // T3：用 /start 返回体里的 state 决定初始显示，不再无条件假定为运行中
        setDeliverState(data.state === 'QUEUED' ? 'QUEUED' : 'RUNNING')
        setBlockedByName(data.blockedByName || '')
      } else {
        // C/Z6：启动失败（请先登录/任务已在运行中/后端异常）必须让用户看见，不能静默
        console.warn('启动失败：', data.message)
        setDeliverState('IDLE')
        setSaveResult({ success: false, message: data.message || '启动失败，请稍后重试' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      console.error('Failed to start delivery:', error)
      // C/Z6：网络异常也要可见
      setDeliverState('IDLE')
      setSaveResult({ success: false, message: `无法连接后端服务（${API_BASE}），请确认服务已启动` })
      setShowSaveDialog(true)
    }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/liepin/stop`, {
        method: 'POST',
      })
      const data = await response.json().catch(() => ({}))

      if (data.success) {
        // 停止成功：排队取消立即回 IDLE；运行中由轮询/看门狗收敛（STOPPING 过渡态由轮询同步）
        const st = await syncDeliveryStatus()
        if (!st) setDeliverState('IDLE')
      } else {
        // G：400 多为竞态——点击瞬间任务已自然结束。拉平状态并提示，
        // 不做处理的话叠加轮询中断会让按钮永久卡在"停止投递"
        await syncDeliveryStatus()
        setSaveResult({ success: true, message: data.message || '任务已结束，无需停止' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      console.error('Failed to stop delivery:', error)
      // G：网络异常时提示，轮询兜底会自行恢复同步
      setSaveResult({ success: false, message: '停止请求发送失败，请稍后查看状态（页面会自动恢复同步）' })
      setShowSaveDialog(true)
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/liepin/logout`, { method: 'POST' })
      const data = await response.json()
      if (data.success) {
        setIsLoggedIn(false)
        setDeliverState('IDLE')
        console.info('已退出登录，数据库Cookie已置空')
        setLogoutResult({ success: true, message: '已退出登录，Cookie已清空。' })
        setShowLogoutResultDialog(true)
      } else {
        console.warn('退出登录失败：', data.message)
        setLogoutResult({ success: false, message: `退出登录失败：${data.message || '服务返回异常。'}` })
        setShowLogoutResultDialog(true)
      }
    } catch (error) {
      console.error('Failed to logout:', error)
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  if (loading) {
    return <div className="flex items-center justify-center h-screen">加载中...</div>
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiSearch className="text-2xl" />}
        title="猎聘配置"
        subtitle="配置猎聘平台的求职参数"
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
                <BiPlay className="mr-1" /> 请先登录猎聘
              </Button>
            ) : deliverState === 'QUEUED' ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-amber-500 to-orange-600 hover:from-amber-600 hover:to-orange-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiStop className="mr-1" /> 取消排队
              </Button>
            ) : deliverState === 'STOPPING' ? (
              // Z4/B：停止请求已发出、看门狗宽限期内（最长 15s）——明确告知而非让用户以为没生效
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
            <Button onClick={() => handleSave()} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
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
        {/* 平台说明 */}
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiBriefcase className="text-primary" />
              猎聘平台说明
            </CardTitle>
            <CardDescription>登录与投递操作提示</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">请在浏览器标签页中登录 猎聘 平台，登录成功后系统会自动检测登录状态。</p>
              <p className="text-sm text-muted-foreground">登录成功后，点击“开始投递”按钮启动自动投递任务。</p>
              <p className="text-sm text-muted-foreground">点击“保存配置”按钮可手动保存当前登录相关信息到数据库。</p>
            </div>
          </CardContent>
        </Card>

        {/* 搜索配置 */}
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiSearch className="text-primary" />
              搜索配置
            </CardTitle>
            <CardDescription>设置职位搜索关键词和筛选条件</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-2">
                <Label htmlFor="keywords">搜索关键词</Label>
                <Input
                  id="keywords"
                  value={config.keywords || ''}
                  onChange={(e) => setConfig({ ...config, keywords: e.target.value })}
                  placeholder="例如：大模型, Python, Golang"
                />
                <p className="text-xs text-muted-foreground">关键词可多选，使用英文逗号分隔，例如：大模型, Python, Golang</p>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <Label htmlFor="city">工作城市</Label>
                  <button
                    type="button"
                    onClick={() => {
                      setIsCustomCity(!isCustomCity)
                      if (!isCustomCity) {
                        // 切换到手动输入时，清空当前值
                        setConfig({ ...config, city: '' })
                      }
                    }}
                    className="text-xs text-primary hover:underline"
                  >
                    {isCustomCity ? '从列表选择' : '手动输入'}
                  </button>
                </div>
                {isCustomCity ? (
                  <Input
                    id="city"
                    value={config.city || ''}
                    onChange={(e) => setConfig({ ...config, city: e.target.value })}
                    placeholder="请输入城市码，例如：410"
                  />
                ) : (
                  <Select
                    id="city"
                    value={config.city || ''}
                    onChange={(e) => setConfig({ ...config, city: e.target.value })}
                  >
                    <option value="">请选择城市</option>
                    {options.city.map((city) => (
                      <option key={city.id} value={city.name}>
                        {city.name}
                      </option>
                    ))}
                  </Select>
                )}
                <p className="text-xs text-muted-foreground">
                  {isCustomCity ? '手动输入城市码（例如：410代表北京）' : '从列表选择城市，或点击"手动输入"自定义'}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 薪资配置 */}
        <Card className="animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiMoney className="text-primary" />
              薪资筛选
            </CardTitle>
            <CardDescription>设置期望薪资范围</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-6">
              <div className="space-y-2">
                <Label htmlFor="salaryCode">薪资范围</Label>
                <Input
                  id="salaryCode"
                  value={config.salaryCode || ''}
                  onChange={(e) => setConfig({ ...config, salaryCode: e.target.value })}
                  placeholder="例如：15$30"
                />
                <p className="text-xs text-muted-foreground">薪资范围码（例如：15$30表示15k-30k）</p>
              </div>
            </div>
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
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiLogOut className="text-red-500" />
                  确认退出登录
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
                <div className="flex justify-end gap-2">
                  <Button
                    variant="ghost"
                    onClick={() => setShowLogoutDialog(false)}
                    className="rounded-full px-4"
                  >
                    取消
                  </Button>
                  <Button
                    onClick={async () => {
                      await triggerLogout()
                      setShowLogoutDialog(false)
                    }}
                    className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4"
                  >
                    确认退出
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiLogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                  {logoutResult.success ? '退出登录成功' : '退出登录失败'}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowLogoutResultDialog(false)}
                    className={`rounded-full px-4 ${logoutResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 保存结果弹框 */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                  {saveResult.success ? '保存成功' : '保存失败'}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowSaveDialog(false)}
                    className={`rounded-full px-4 ${saveResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </div>
  )
}
