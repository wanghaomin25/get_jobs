'use client'

import { useState, useEffect } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { BiLogOut, BiSave, BiBriefcase, BiPlay, BiStop } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import PageHeader from '@/app/components/PageHeader'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import AnalysisContent from '@/app/51job/analysis/AnalysisContent'
import { API_BASE } from '@/lib/api'

interface Job51Config {
  id?: number
  keywords?: string
  jobArea?: string
  salary?: string // 存储JSON数组字符串，如 ["03","04","05"]
  jobNature?: string // 职位类型（单值：''=全部、01=全职、02=兼职、03=实习）
}

interface Job51Option { name: string; code: string }
interface Job51Options { jobArea: Job51Option[]; salary: Job51Option[]; jobNature: Job51Option[] }

// 薪资选择状态（用于多选）
const MAX_SALARY_SELECTIONS = 5

export default function Job51Page() {
  const API = process.env.API_BASE_URL || `${API_BASE}`
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  // T3：投递状态以后端为准（IDLE/QUEUED/RUNNING），解决"刷新页面后状态归零"的问题
  // Z4/B：RUNNING 且后端 stopRequested=true 时映射为 STOPPING 过渡态（按钮显示"停止中…"）
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

  const [config, setConfig] = useState<Job51Config>({ keywords: '', jobArea: '', salary: '', jobNature: '' })
  const [options, setOptions] = useState<Job51Options>({ jobArea: [], salary: [], jobNature: [] })
  const [loadingConfig, setLoadingConfig] = useState(true)
  const [isCustomArea, setIsCustomArea] = useState(false)
  const [backendAvailable, setBackendAvailable] = useState(false)
  const [cookieSavedAfterLogin, setCookieSavedAfterLogin] = useState(false)
  // 薪资多选状态：存储选中的code数组
  const [selectedSalaries, setSelectedSalaries] = useState<string[]>([])
  // 薪资下拉面板开关状态
  const [salaryDropdownOpen, setSalaryDropdownOpen] = useState(false)

  useEffect(() => {
    if (!backendAvailable) {
      setCheckingLogin(false)
      return
    }
    console.log('[51job] useEffect 开始执行')
    console.log('[51job] window:', typeof window)
    console.log('[51job] EventSource:', typeof EventSource)

    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('[51job] EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API}/api/jobs/login-status/stream`, {
      onOpen: () => {
        console.log('[51job SSE] ✅ 连接已打开')
      },
      onError: (e, attempt, delay) => {
        console.warn(`[51job SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            console.log('[51job SSE] ✅ 收到 connected 事件:', event.data)
            try {
              const data = JSON.parse(event.data)
              setIsLoggedIn(data.job51LoggedIn || false)
              if (data.job51LoggedIn && !cookieSavedAfterLogin) {
                fetch(`${API}/api/cookie/save?platform=51job`, { method: 'POST' }).catch(() => {})
                setCookieSavedAfterLogin(true)
              }
              setCheckingLogin(false)
            } catch (error) {
              console.error('[51job SSE] ❌ 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              if (data.platform === '51job') {
                setIsLoggedIn(data.isLoggedIn)
                if (data.isLoggedIn && !cookieSavedAfterLogin) {
                  fetch(`${API}/api/cookie/save?platform=51job`, { method: 'POST' }).catch(() => {})
                  setCookieSavedAfterLogin(true)
                }
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[51job SSE] ❌ 解析登录状态消息失败:', error)
            }
          },
        },
        {
          name: 'ping',
          handler: () => {
            // 心跳事件，无需处理，可以用于调试：console.debug('[51job SSE] ♥ ping')
          },
        },
      ],
    })

    return () => {
      console.log('[51job SSE] 🔌 关闭SSE连接')
      client.close()
    }
  }, [backendAvailable])

  // 点击外部关闭薪资下拉面板
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as HTMLElement
      // 如果点击的不是薪资下拉框或其子元素，则关闭
      if (!target.closest('.salary-dropdown-container')) {
        setSalaryDropdownOpen(false)
      }
    }

    if (salaryDropdownOpen) {
      document.addEventListener('mousedown', handleClickOutside)
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [salaryDropdownOpen])

  // T3：投递状态以后端为准——挂载时查询一次，非 IDLE 时每 3 秒轮询同步
  // Z3/A：查询失败不再中断轮询链（失败降频 5s 重试），否则停止后状态永远无法同步回 IDLE
  const syncDeliveryStatus = async (): Promise<'IDLE' | 'QUEUED' | 'RUNNING' | 'STOPPING' | null> => {
    try {
      const res = await fetch(`${API}/api/51job/status`)
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
      console.warn('[51job] 查询投递状态失败（后端可能未启动）:', e)
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

  // 解析/序列化关键词（与猎聘保持一致）
  const parseKeywordsFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) return arr.filter(Boolean).join(', ')
      } catch (e) {
        console.warn('[51job] 解析关键词JSON失败，使用原值:', e)
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

  // 解析后端存储的列表字符串，返回第一个元素（用于单选）
  const parseSingleTokenFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr) && arr.length > 0) {
          return String(arr[0] ?? '').trim()
        }
      } catch (_) {
        // ignore, fall through
      }
    }
    // 非 JSON 数组时，按逗号拆分，取第一个
    const parts = t.replace(/，/g, ',').split(',').map((s) => s.trim()).filter(Boolean)
    return parts[0] || ''
  }

  // 解析后端存储的薪资列表（多选）
  const parseMultiTokensFromDb = (raw?: string): string[] => {
    if (!raw) return []
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) {
          return arr.map(v => String(v ?? '').trim()).filter(Boolean)
        }
      } catch (_) {
        // ignore, fall through
      }
    }
    // 非 JSON 数组时，按逗号拆分
    return t.replace(/，/g, ',').split(',').map((s) => s.trim()).filter(Boolean)
  }

  const fetchAllData = async () => {
    try {
      const res = await fetch(`${API}/api/51job/config`)
      if (!res.ok) {
        console.warn(`[51job] 获取配置失败: ${res.status}`)
        setConfig({ keywords: '', jobArea: '', salary: '', jobNature: '' })
        setOptions({ jobArea: [], salary: [], jobNature: [] })
        return
      }
      const data = await res.json()
      if (data.config || data.options) {
        const opts: Job51Options = data.options || { jobArea: [], salary: [], jobNature: [] }
        const conf: Job51Config = data.config || { keywords: '', jobArea: '', salary: '', jobNature: '' }

        // 关键词标准化（展示用）
        const normalizedKeywords = parseKeywordsFromDb(conf.keywords)

        // 从服务端字段提取第一个token，然后映射到code
        const rawArea = parseSingleTokenFromDb(conf.jobArea)
        const rawSalaries = parseMultiTokensFromDb(conf.salary) // 薪资支持多个

        const areaList = opts.jobArea || []
        const salaryList = opts.salary || []

        const matchArea = areaList.find((o) => o.code === rawArea || o.name === rawArea)
        
        // 薪资多选：将每个值映射为code
        const salaryCodes = rawSalaries
          .map(raw => {
            const match = salaryList.find(o => o.code === raw || o.name === raw)
            return match?.code || raw
          })
          .filter(Boolean)
          .slice(0, MAX_SALARY_SELECTIONS) // 最多5个

        const areaCode = matchArea?.code || (areaList.find((o) => o.name === '不限')?.code || areaList.find((o) => o.code === '0')?.code || '')

        // 职位类型：name/code 双匹配映射（同智联），空/全部 → ''
        const rawNature = parseSingleTokenFromDb(conf.jobNature)
        const natureList = opts.jobNature || []
        const matchNature = natureList.find((o) => o.code === rawNature || o.name === rawNature)
        const natureCode = matchNature?.code ?? rawNature ?? ''

        setOptions(opts)
        setConfig({ ...conf, keywords: normalizedKeywords, jobArea: areaCode, salary: JSON.stringify(salaryCodes), jobNature: natureCode })
        setSelectedSalaries(salaryCodes)
        setIsCustomArea(false)
      }
    } catch (e) {
      console.warn('[51job] 获取配置异常（可能后端未启动）:', e)
    } finally {
      setLoadingConfig(false)
    }
  }

  // 后端可用性探测：成功则加载配置，否则停止加载并禁用SSE
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch(`${API}/api/51job/config`, { method: 'GET' })
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
  }, [])

  const handleStartDelivery = async () => {
    try {
      // 先把页面上的配置落库：否则用户改了关键词但没点“保存配置”时，后端读到的还是旧值/空值
      // （job51_config 曾长期为 0 行，后端直接返回“关键词列表为空”就是这个原因）。
      // silent=true：保存成功不打断投递；保存失败则弹框说明原因并中止启动。
      const saved = await handleSaveConfig(true)
      if (!saved) {
        setShowSaveDialog(true)
        return
      }
      const response = await fetch(`${API}/api/51job/start`, { method: 'POST' })
      const data = await response.json()
      if (data.success) {
        // T3：用 /start 返回体里的 state 决定初始显示，不再无条件假定为运行中
        setDeliverState(data.state === 'QUEUED' ? 'QUEUED' : 'RUNNING')
        setBlockedByName(data.blockedByName || '')
      } else {
        // E/Z6：启动失败（请先登录/任务已在运行中/后端异常）必须让用户看见，不能静默
        console.warn('[51job] 启动失败：', data.message)
        setSaveResult({ success: false, message: data.message || '启动失败，请稍后重试' })
        setShowSaveDialog(true)
        setDeliverState('IDLE')
      }
    } catch (error) {
      console.error('[51job] 启动投递失败：', error)
      // E/Z6：网络异常也要可见
      setSaveResult({ success: false, message: `无法连接后端服务（${API}），请确认服务已启动` })
      setShowSaveDialog(true)
      setDeliverState('IDLE')
    }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch(`${API}/api/51job/stop`, { method: 'POST' })
      if (!response.ok) {
        // G：后端返回错误状态码多为竞态——点击瞬间任务已自然结束。拉平状态并提示，
        // 不做处理的话叠加轮询中断会让按钮永久卡在"停止投递"
        console.warn('[51job] 停止投递请求失败，状态码:', response.status)
        await syncDeliveryStatus()
        setSaveResult({ success: true, message: '任务已结束，无需停止' })
        setShowSaveDialog(true)
        return
      }

      const data = await response.json()
      console.log('[51job] 停止投递响应:', data)

      if (data.success) {
        // 停止成功：排队取消立即回 IDLE；运行中由轮询/看门狗收敛
        const st = await syncDeliveryStatus()
        if (!st) setDeliverState('IDLE')
      } else {
        // G：停止失败（可能任务未运行），拉平状态并提示
        console.warn('[51job] 停止投递失败:', data.message)
        await syncDeliveryStatus()
        setSaveResult({ success: true, message: data.message || '任务已结束，无需停止' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      // G：网络异常时提示，轮询兜底会自行恢复同步
      console.error('[51job] 停止投递请求异常:', error)
      setSaveResult({ success: false, message: '停止请求发送失败，请稍后查看状态（页面会自动恢复同步）' })
      setShowSaveDialog(true)
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(`${API}/api/51job/logout`, { method: 'POST' })
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
      const response = await fetch(`${API}/api/cookie/save?platform=51job`, { method: 'POST' })
      const data = await response.json()
      setSaveResult({ success: data.success, message: data.success ? '配置保存成功。' : data.message })
      setShowSaveDialog(true)
    } catch (error) {
      setSaveResult({ success: false, message: '配置保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  const handleSaveConfig = async (silent?: boolean): Promise<boolean> => {
    try {
      // 将 jobArea / salary 统一为“中文名”的括号列表字符串，满足后端保存中文的要求
      const toBracketListString = (v?: string, type?: 'jobArea' | 'salary') => {
        const t = (v || '').trim()
        if (!t) return '[]'
        if (type === 'jobArea') {
          // 城市允许手动输入，若下拉匹配不到则直接保存输入值
          const match = (options.jobArea || []).find((o) => o.code === t || o.name === t)
          const name = match?.name || t
          return `["${name.replace(/"/g, '\\"')}"]`
        }
        if (type === 'salary') {
          // 薪资多选：将selectedSalaries数组映射为中文名数组
          const names = selectedSalaries
            .map(code => {
              const match = (options.salary || []).find(o => o.code === code)
              return match?.name || ''
            })
            .filter(Boolean)
          return names.length > 0 ? JSON.stringify(names) : '[]'
        }
        return `["${t.replace(/"/g, '\\"')}"]"`
      }
      const payload = {
        ...config,
        keywords: serializeKeywordsForDb(config.keywords),
        jobArea: toBracketListString(config.jobArea, 'jobArea'),
        salary: toBracketListString(config.salary, 'salary'),
      }
      const response = await fetch(`${API}/api/51job/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      let ok = false
      if (response.ok) {
        // 保存配置成功后，同步保存 Cookie（按你的要求加到保存按钮）
        try {
          await fetch(`${API}/api/cookie/save?platform=51job`, { method: 'POST' })
        } catch (e) {
          console.warn('[51job] 保存 Cookie 失败:', e)
        }
        await fetchAllData()
        setSaveResult({ success: true, message: '保存成功，配置与Cookie已更新。' })
        ok = true
      } else {
        setSaveResult({ success: false, message: '保存失败：后端返回异常状态。' })
      }
      // silent 用于“开始投递”前的静默保存：成功时不弹框打断流程
      if (!silent) setShowSaveDialog(true)
      return ok
    } catch (error) {
      console.error('[51job] 保存配置失败:', error)
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      if (!silent) setShowSaveDialog(true)
      return false
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="51job配置"
        subtitle="配置51job平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-blue-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 请先登录51job
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
          {/* 平台说明 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                51job 平台说明
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <p className="text-sm text-muted-foreground">请在浏览器标签页中登录 51job 平台，登录成功后系统会自动检测登录状态。</p>
                <p className="text-sm text-muted-foreground">登录成功后，点击“开始投递”按钮启动自动投递任务。</p>
                <p className="text-sm text-muted-foreground">点击“保存配置”按钮可手动保存当前登录相关信息到数据库。</p>
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
                    <div className="flex items-center justify-between">
                      <Label>城市区域</Label>
                      <button
                        type="button"
                        onClick={() => {
                          setIsCustomArea(!isCustomArea)
                          if (!isCustomArea) setConfig((c) => ({ ...c, jobArea: '' }))
                        }}
                        className="text-xs text-primary hover:underline"
                      >
                        {isCustomArea ? '从列表选择' : '手动输入'}
                      </button>
                    </div>
                    {isCustomArea ? (
                      <Input
                        placeholder="请输入51job城市码，例如：010000"
                        value={config.jobArea || ''}
                        onChange={(e) => setConfig((c) => ({ ...c, jobArea: e.target.value }))}
                      />
                    ) : (
                      <Select
                        value={config.jobArea || ''}
                        onChange={(e) => setConfig((c) => ({ ...c, jobArea: e.target.value }))}
                        placeholder="请选择城市"
                      >
                        <option value="">请选择城市</option>
                        {options.jobArea.map((o) => (
                          <option key={o.code} value={o.code}>{o.name}</option>
                        ))}
                      </Select>
                    )}
                    <p className="text-xs text-muted-foreground">
                      {isCustomArea ? '手动输入6位城市码（如 010000=北京，080200=杭州）' : '从列表选择城市，或点击"手动输入"自定义'}
                    </p>
                  </div>
                  <div className="space-y-2">
                    <Label>职位类型</Label>
                    <Select
                      value={config.jobNature || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, jobNature: e.target.value }))}
                      placeholder="全部"
                    >
                      {options.jobNature.map((o) => (
                        <option key={o.code || 'all'} value={o.code}>{o.name}</option>
                      ))}
                    </Select>
                    <p className="text-xs text-muted-foreground">
                      按工作性质过滤岗位：全部/全职/兼职/实习（默认全部=不过滤）
                    </p>
                  </div>
                  <div className="space-y-2">
                    <Label>薪资范围（最多选择5个）</Label>
                    <div className="relative salary-dropdown-container">
                      {/* 下拉多选框 */}
                      <div
                        className="flex h-10 w-full rounded-full px-4 py-2 text-sm border border-white/40 bg-white/5 shadow-[inset_0_1px_0_rgba(255,255,255,.25)] cursor-pointer hover:bg-white/10 transition-all duration-200"
                        onClick={() => setSalaryDropdownOpen(!salaryDropdownOpen)}
                      >
                        <span className="truncate text-sm">
                          {selectedSalaries.length > 0
                            ? selectedSalaries
                                .map((code) => options.salary.find((o) => o.code === code)?.name)
                                .filter(Boolean)
                                .join(', ')
                            : '请选择薪资范围'}
                        </span>
                      </div>
                      {/* 下拉选项面板 */}
                      {salaryDropdownOpen && (
                        <div className="dropdown-panel" style={{ position: 'absolute', marginTop: '8px', width: '100%' }}>
                          <ul className="py-1">
                            {options.salary.map((o) => {
                              const isSelected = selectedSalaries.includes(o.code)
                              const canSelect = selectedSalaries.length < MAX_SALARY_SELECTIONS || isSelected
                              return (
                                <li
                                  key={o.code}
                                  className={`group flex items-center gap-3 px-3 py-2 cursor-pointer transition-all border-b border-white/12 last:border-b-0 ${
                                    !canSelect ? 'opacity-50 cursor-not-allowed' : 'hover:bg-white/12'
                                  } ${
                                    isSelected ? 'bg-gradient-to-r from-emerald-500/12 to-cyan-500/12' : ''
                                  }`}
                                  onClick={() => {
                                    if (!canSelect) return
                                    let newSalaries: string[]
                                    if (isSelected) {
                                      newSalaries = selectedSalaries.filter((code) => code !== o.code)
                                    } else {
                                      if (selectedSalaries.length < MAX_SALARY_SELECTIONS) {
                                        newSalaries = [...selectedSalaries, o.code]
                                      } else {
                                        return
                                      }
                                    }
                                    setSelectedSalaries(newSalaries)
                                    setConfig((c) => ({ ...c, salary: JSON.stringify(newSalaries) }))
                                  }}
                                >
                                  <span
                                    className={`inline-flex h-4 w-4 items-center justify-center rounded-md border border-white/30 bg-white/10 shadow-inner transition-all ${
                                      isSelected ? 'bg-emerald-400/60 border-emerald-300/80' : ''
                                    }`}
                                  >
                                    {isSelected && (
                                      <svg className="w-3 h-3 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                                      </svg>
                                    )}
                                  </span>
                                  <span className="text-sm truncate">{o.name}</span>
                                </li>
                              )
                            })}
                          </ul>
                        </div>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      已选择 {selectedSalaries.length}/{MAX_SALARY_SELECTIONS} 个薪资范围
                    </p>
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
