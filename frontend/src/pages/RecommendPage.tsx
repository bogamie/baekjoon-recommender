import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  getRecommendations,
  skipProblem,
  refreshRecommendations,
  RecommendationResponse,
  RecommendationSlot,
} from '../api/recommend'
import UserMenu from '../components/UserMenu'
import Footer from '../components/Footer'
import styles from './RecommendPage.module.css'

const SLOT_LABELS: Record<string, string> = {
  REVIEW: '복습',
  GROWTH: '성장',
  CHALLENGE: '도전',
  ENTRY: '입문',
  EXPLORE: '탐험',
}

export default function RecommendPage() {
  const navigate = useNavigate()

  const [data, setData] = useState<RecommendationResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    fetchRecommendations()
  }, [])

  async function fetchRecommendations() {
    setLoading(true)
    setError('')
    try {
      const res = await getRecommendations()
      setData(res.data)
    } catch (err: any) {
      const msg = err.response?.data?.message || '추천을 불러오지 못했습니다'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  async function handleRefresh() {
    setRefreshing(true)
    try {
      const res = await refreshRecommendations()
      setData(res.data)
    } catch {
      // ignore
    } finally {
      setRefreshing(false)
    }
  }

  async function handleSkip(slot: RecommendationSlot) {
    try {
      await skipProblem(slot.problem.id)
      // Remove from list
      if (data) {
        setData({
          ...data,
          recommendations: data.recommendations.filter(
            (r) => r.problem.id !== slot.problem.id
          ),
        })
      }
    } catch {
      // ignore
    }
  }

  return (
    <div className={styles.page}>
      <nav className={styles.nav}>
        <div className={styles.navInner}>
          <span className={styles.brand} onClick={() => navigate('/dashboard')}>
            BaekjoonRec
          </span>
          <UserMenu />
        </div>
      </nav>

      <main className={styles.content}>
        {loading ? (
          <p className={styles.stateMsg}>추천을 생성하는 중...</p>
        ) : error ? (
          <p className={styles.stateMsg}>{error}</p>
        ) : data ? (
          <>
            <div className={styles.header}>
              <h1 className={styles.title}>오늘의 추천</h1>
              <button
                className={styles.refreshBtn}
                onClick={handleRefresh}
                disabled={refreshing}
              >
                {refreshing ? '새로고침 중...' : '전체 새로고침'}
              </button>
            </div>

            {data.userProfile && (
              <div className={styles.profileSection}>
                <span>{data.userProfile.tier}</span>
                <span>{data.userProfile.totalSolved}문제</span>
                <span>상급 {data.userProfile.advancedCount}</span>
                <span>중급 {data.userProfile.intermediateCount}</span>
                <span>초급 {data.userProfile.beginnerCount}</span>
              </div>
            )}

            {data.recommendations.length === 0 ? (
              <p className={styles.emptyMsg}>
                추천할 문제가 없습니다. 데이터를 동기화해주세요.
              </p>
            ) : (
              <div className={styles.cardList}>
                {data.recommendations.map((slot) => (
                  <div key={slot.problem.id} className={styles.card}>
                    <div className={styles.cardHeader}>
                      <span
                        className={`${styles.slotLabel} ${styles[`slot${slot.slotType}`]}`}
                      >
                        <span className={styles.slotLabelText}>
                          {SLOT_LABELS[slot.slotType] || slot.slotType}
                        </span>
                      </span>
                      <span className={styles.levelBadge}>
                        {slot.problem.levelName}
                      </span>
                    </div>

                    <p className={styles.problemTitle}>
                      <span className={styles.problemId}>#{slot.problem.id}</span>{' '}
                      {slot.problem.title}
                    </p>

                    <p className={styles.tagList}>
                      {slot.problem.tags.join(' · ')}
                    </p>

                    <p className={styles.reason}>{slot.reason.message}</p>

                    <div className={styles.cardActions}>
                      <button
                        className={styles.skipBtn}
                        onClick={() => handleSkip(slot)}
                      >
                        스킵
                      </button>
                      <a
                        className={styles.solveBtn}
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
            )}
          </>
        ) : null}
      </main>
      <Footer />
    </div>
  )
}
