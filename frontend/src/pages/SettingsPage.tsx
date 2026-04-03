import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { updateSolvedac, syncData, updateSettings } from '../api/user'
import UserMenu from '../components/UserMenu'
import styles from './SettingsPage.module.css'

export default function SettingsPage() {
  const navigate = useNavigate()
  const { user, setUser } = useAuth()

  const [handleInput, setHandleInput] = useState(user?.solvedacHandle || '')
  const [handleSaving, setHandleSaving] = useState(false)
  const [handleSyncing, setHandleSyncing] = useState(false)
  const [handleMsg, setHandleMsg] = useState('')
  const [handleError, setHandleError] = useState(false)

  const [themeSaving, setThemeSaving] = useState(false)
  const [foreignSaving, setForeignSaving] = useState(false)

  async function handleSaveHandle() {
    if (!handleInput.trim()) return
    setHandleSaving(true)
    setHandleMsg('')
    try {
      const res = await updateSolvedac(handleInput.trim())
      setUser({ ...user!, solvedacHandle: res.data.solvedacHandle || handleInput.trim() })
      setHandleError(false)
      setHandleSaving(false)
      setHandleSyncing(true)
      setHandleMsg('데이터 동기화 중...')
      // 동기화는 백그라운드로 실행
      syncData()
        .then(() => { setHandleMsg('연동 및 동기화가 완료되었습니다'); setHandleSyncing(false) })
        .catch(() => { setHandleMsg('연동되었지만 동기화에 실패했습니다. 대시보드에서 동기화를 다시 시도해주세요'); setHandleSyncing(false) })
    } catch {
      setHandleMsg('연동에 실패했습니다. 아이디를 확인해주세요')
      setHandleError(true)
      setHandleSaving(false)
    }
  }

  async function handleThemeChange(theme: string) {
    setThemeSaving(true)
    try {
      const res = await updateSettings({ theme })
      setUser({ ...user!, theme: res.data.theme })
    } catch {
      // ignore
    } finally {
      setThemeSaving(false)
    }
  }

  async function handleForeignToggle() {
    setForeignSaving(true)
    try {
      const newVal = !user?.includeForeign
      const res = await updateSettings({ includeForeign: newVal })
      setUser({ ...user!, includeForeign: res.data.includeForeign })
    } catch {
      // ignore
    } finally {
      setForeignSaving(false)
    }
  }

  const currentTheme = user?.theme || 'light'
  const includeForeign = user?.includeForeign !== false

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
        <h1 className={styles.title}>설정</h1>

        {/* Handle */}
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>solved.ac 핸들</h2>
          <p className={styles.sectionDesc}>
            solved.ac 핸들을 변경하면 데이터가 새로 동기화됩니다
          </p>
          <div className={styles.handleRow}>
            <input
              className={styles.handleInput}
              type="text"
              placeholder="solved.ac 핸들"
              value={handleInput}
              onChange={(e) => setHandleInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSaveHandle()}
            />
            <button
              className={styles.saveBtn}
              onClick={handleSaveHandle}
              disabled={handleSaving || !handleInput.trim()}
            >
              {handleSaving ? '저장 중...' : '저장'}
            </button>
          </div>
          {handleMsg && (
            <p className={handleError ? styles.errorMsg : styles.successMsg}>
              {handleSyncing && <span className={styles.spinner} />}
              {handleMsg}
            </p>
          )}
        </div>

        {/* Theme */}
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>테마</h2>
          <p className={styles.sectionDesc}>화면 테마를 선택합니다</p>
          <div className={styles.themeOptions}>
            <button
              className={`${styles.themeBtn} ${currentTheme === 'light' ? styles.selected : ''}`}
              onClick={() => handleThemeChange('light')}
              disabled={themeSaving}
            >
              라이트
            </button>
            <button
              className={`${styles.themeBtn} ${currentTheme === 'dark' ? styles.selected : ''}`}
              onClick={() => handleThemeChange('dark')}
              disabled={themeSaving}
            >
              다크
            </button>
          </div>
        </div>

        {/* Foreign language */}
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>외국어 문제</h2>
          <p className={styles.sectionDesc}>
            추천에 한국어 번역이 없는 외국어 문제를 포함할지 설정합니다
          </p>
          <div className={styles.toggleRow}>
            <button
              className={`${styles.toggle} ${includeForeign ? styles.active : ''}`}
              onClick={handleForeignToggle}
              disabled={foreignSaving}
            >
              <span className={styles.toggleKnob} />
            </button>
            <span className={styles.toggleLabel}>
              {includeForeign ? '외국어 문제 포함' : '한국어 문제만'}
            </span>
          </div>
        </div>
      </main>
    </div>
  )
}
