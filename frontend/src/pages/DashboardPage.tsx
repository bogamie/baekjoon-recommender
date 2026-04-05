import { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../contexts/AuthContext'
import { updateSolvedac, syncData } from '../api/user'
import { getSummary, getTagStats, Summary, TagStat } from '../api/dashboard'
import {
  getRecommendations,
  skipProblem,
  refreshRecommendations,
  RecommendationResponse,
  RecommendationSlot,
} from '../api/recommend'
import UserMenu from '../components/UserMenu'
import Footer from '../components/Footer'
import styles from './DashboardPage.module.css'

// Tier helpers
const TIER_COLORS: Record<string, string> = {
  Bronze: '#AD5600',
  Silver: '#435F7A',
  Gold: '#EC9A00',
  Platinum: '#27E2A4',
  Diamond: '#00B4FC',
  Ruby: '#FF0062',
}

function tierName(tier: number): string {
  if (tier === 0) return 'Unrated'
  const groups = ['Bronze', 'Silver', 'Gold', 'Platinum', 'Diamond', 'Ruby']
  const groupIndex = Math.floor((tier - 1) / 5)
  const rank = 5 - ((tier - 1) % 5)
  const group = groups[Math.min(groupIndex, groups.length - 1)]
  return `${group} ${rank}`
}

function tierColor(tier: number): string {
  if (tier === 0) return '#6B7280'
  const groups = ['Bronze', 'Silver', 'Gold', 'Platinum', 'Diamond', 'Ruby']
  const groupIndex = Math.floor((tier - 1) / 5)
  const group = groups[Math.min(groupIndex, groups.length - 1)]
  return TIER_COLORS[group] ?? '#6B7280'
}

type SortKey = keyof Pick<TagStat, 'tagKey' | 'solvedCount' | 'avgLevel' | 'maxLevel' | 'lastSolvedAt' | 'proficiency'>
type SortDir = 'asc' | 'desc'

const SLOT_LABELS: Record<string, string> = {
  REVIEW: '복습',
  GROWTH: '성장',
  CHALLENGE: '도전',
  ENTRY: '입문',
  EXPLORE: '탐험',
}

const SLOT_COLORS: Record<string, string> = {
  REVIEW: '#6B7280',
  GROWTH: '#3B82F6',
  CHALLENGE: '#EF4444',
  ENTRY: '#10B981',
  EXPLORE: '#8B5CF6',
}

function formatDate(dateStr: string): string {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return '-'
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
}

export default function DashboardPage() {
  const { user } = useAuth()

  const [summary, setSummary] = useState<Summary | null>(null)
  const [tagStats, setTagStats] = useState<TagStat[]>([])
  const [loadingData, setLoadingData] = useState(true)
  const [syncing, setSyncing] = useState(false)

  const [handleInput, setHandleInput] = useState('')
  const [linking, setLinking] = useState(false)
  const [linkError, setLinkError] = useState('')

  const [sortKey, setSortKey] = useState<SortKey>('solvedCount')
  const [sortDir, setSortDir] = useState<SortDir>('desc')

  // Recommendations
  const [recData, setRecData] = useState<RecommendationResponse | null>(null)
  const [loadingRec, setLoadingRec] = useState(false)
  const [refreshingRec, setRefreshingRec] = useState(false)

  const hasSolvedac = !!user?.solvedacHandle

  const fetchData = useCallback(async () => {
    if (!hasSolvedac) {
      setLoadingData(false)
      return
    }
    setLoadingData(true)
    try {
      const [summaryRes, tagRes] = await Promise.all([getSummary(), getTagStats()])
      setSummary(summaryRes.data)
      setTagStats(tagRes.data)
    } catch (err) {
      console.error('Failed to fetch dashboard data:', err)
    } finally {
      setLoadingData(false)
    }
  }, [hasSolvedac])

  const fetchRecommendations = useCallback(async () => {
    if (!hasSolvedac) return
    setLoadingRec(true)
    try {
      const res = await getRecommendations()
      setRecData(res.data)
    } catch (err) {
      console.error('Failed to fetch recommendations:', err)
    } finally {
      setLoadingRec(false)
    }
  }, [hasSolvedac])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  useEffect(() => {
    if (!loadingData && hasSolvedac && tagStats.length > 0) {
      fetchRecommendations()
    }
  }, [loadingData, hasSolvedac, tagStats.length, fetchRecommendations])

  async function handleLink() {
    if (!handleInput.trim()) return
    setLinking(true)
    setLinkError('')
    try {
      await updateSolvedac(handleInput.trim())
      await syncData()
      window.location.reload()
    } catch {
      setLinkError('연동에 실패했습니다. 아이디를 확인해주세요')
      setLinking(false)
    }
  }

  async function handleSync() {
    setSyncing(true)
    try {
      await syncData()
      await fetchData()
      await fetchRecommendations()
    } catch {
      // ignore
    } finally {
      setSyncing(false)
    }
  }

  async function handleRefreshRec() {
    setRefreshingRec(true)
    try {
      const res = await refreshRecommendations()
      setRecData(res.data)
    } catch {
      // ignore
    } finally {
      setRefreshingRec(false)
    }
  }

  async function handleSkip(slot: RecommendationSlot) {
    try {
      await skipProblem(slot.problem.id)
      if (recData) {
        setRecData({
          ...recData,
          recommendations: recData.recommendations.filter(
            (r) => r.problem.id !== slot.problem.id
          ),
        })
      }
    } catch {
      // ignore
    }
  }

  function handleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir('desc')
    }
  }

  function sortArrow(key: SortKey) {
    if (sortKey !== key) return null
    return <span className={styles.sortArrow}>{sortDir === 'asc' ? '↑' : '↓'}</span>
  }

  const sortedStats = [...tagStats].sort((a, b) => {
    const aVal = a[sortKey]
    const bVal = b[sortKey]
    const dir = sortDir === 'asc' ? 1 : -1
    if (typeof aVal === 'number' && typeof bVal === 'number') {
      return (aVal - bVal) * dir
    }
    return String(aVal).localeCompare(String(bVal)) * dir
  })

  const columns: { key: SortKey; label: string }[] = [
    { key: 'tagKey', label: '태그' },
    { key: 'solvedCount', label: '풀이 수' },
    { key: 'avgLevel', label: '평균 난이도' },
    { key: 'maxLevel', label: '최고 난이도' },
    { key: 'lastSolvedAt', label: '마지막 풀이' },
    { key: 'proficiency', label: '상태' },
  ]

  return (
    <div className={styles.page}>
      {/* Nav */}
      <nav className={styles.nav}>
        <div className={styles.navInner}>
          <span className={styles.brand}>BaekjoonRec</span>
          <UserMenu />
        </div>
      </nav>

      {/* Content */}
      <main className={styles.content}>
        {/* No solvedac linked */}
        {!hasSolvedac && (
          <div className={styles.linkPrompt}>
            <p className={styles.linkPromptText}>
              solved.ac 아이디를 연동하면 풀이 현황을 볼 수 있어요
            </p>
            <div className={styles.linkRow}>
              <input
                className={styles.linkInput}
                type="text"
                placeholder="solved.ac 핸들"
                value={handleInput}
                onChange={(e) => setHandleInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleLink()}
              />
              <button
                className={styles.linkBtn}
                onClick={handleLink}
                disabled={linking || !handleInput.trim()}
              >
                {linking ? '연동 중...' : '연동'}
              </button>
            </div>
            {linkError && <p className={styles.errorMsg}>{linkError}</p>}
          </div>
        )}

        {/* Has solvedac */}
        {hasSolvedac && (
          <>
            {loadingData ? (
              <p className={styles.stateMsg}>불러오는 중...</p>
            ) : (
              <>
                {/* Summary */}
                {summary && (
                  <section className={styles.summarySection}>
                    <p className={styles.summaryLine}>
                      {user?.solvedacHandle} · {tierName(summary.solvedacTier ?? 0)} · {summary.rating ?? 0} · {summary.totalSolved}문제
                    </p>
                    <div className={styles.summaryMeta}>
                      <span className={styles.activityBadge}>{summary.globalStatus}</span>
                      <button
                        className={styles.syncBtn}
                        onClick={handleSync}
                        disabled={syncing}
                      >
                        {syncing ? '동기화 중...' : '동기화'}
                      </button>
                    </div>
                  </section>
                )}

                {/* Recommendations */}
                <section className={styles.recSection}>
                  <div className={styles.recHeader}>
                    <h2 className={styles.recTitle}>오늘의 추천</h2>
                    <button
                      className={styles.syncBtn}
                      onClick={handleRefreshRec}
                      disabled={refreshingRec || loadingRec}
                    >
                      {refreshingRec ? '새로고침 중...' : '새로고침'}
                    </button>
                  </div>

                  {loadingRec ? (
                    <p className={styles.recLoading}>추천을 생성하는 중...</p>
                  ) : recData && recData.recommendations.length > 0 ? (
                    <div className={styles.recCards}>
                      {recData.recommendations.map((slot) => (
                        <div key={slot.problem.id} className={styles.recCard}>
                          <div className={styles.recCardTop}>
                            <span
                              className={styles.slotLabel}
                              style={{ backgroundColor: SLOT_COLORS[slot.slotType] || '#6B7280' }}
                            >
                              {SLOT_LABELS[slot.slotType] || slot.slotType}
                            </span>
                            <span className={styles.recLevel}>{slot.problem.levelName}</span>
                          </div>
                          <p className={styles.recProblemTitle}>
                            <span className={styles.recProblemId}>#{slot.problem.id}</span>{' '}
                            {slot.problem.title}
                          </p>
                          <p className={styles.recTags}>{slot.problem.tags.join(' · ')}</p>
                          <p className={styles.recReason}>{slot.reason.message}</p>
                          <div className={styles.recActions}>
                            <button className={styles.recSkipBtn} onClick={() => handleSkip(slot)}>
                              스킵
                            </button>
                            <a
                              className={styles.recSolveBtn}
                              href={`https://www.acmicpc.net/problem/${slot.problem.id}`}
                              target="_blank"
                              rel="noopener noreferrer"
                            >
                              풀러가기
                            </a>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className={styles.recLoading}>추천할 문제가 없습니다</p>
                  )}
                </section>

                {/* Tag stats */}
                {sortedStats.length === 0 ? (
                  <p className={styles.stateMsg}>태그 데이터가 없습니다</p>
                ) : (
                  <div className={styles.tableSection}>
                    <h2 className={styles.recTitle}>태그별 통계</h2>
                    <table className={styles.table}>
                      <thead>
                        <tr>
                          {columns.map((col) => (
                            <th key={col.key} onClick={() => handleSort(col.key)}>
                              {col.label}
                              {sortArrow(col.key)}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {sortedStats.map((row) => (
                          <tr
                            key={row.tagKey}
                            className={row.isDormant ? styles.dormantRow : ''}
                          >
                            <td>{row.tagKey}</td>
                            <td>{row.solvedCount}</td>
                            <td>
                              <div className={styles.tierCell}>
                                <span
                                  className={styles.tierDot}
                                  style={{ backgroundColor: tierColor(Math.round(row.avgLevel)) }}
                                />
                                {tierName(Math.round(row.avgLevel))}
                              </div>
                            </td>
                            <td>
                              <div className={styles.tierCell}>
                                <span
                                  className={styles.tierDot}
                                  style={{ backgroundColor: tierColor(row.maxLevel) }}
                                />
                                {tierName(row.maxLevel)}
                              </div>
                            </td>
                            <td>{formatDate(row.lastSolvedAt)}</td>
                            <td>
                              <span className={styles.profBadge}>{row.proficiency}</span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </>
        )}
      </main>
      <Footer />
    </div>
  )
}
