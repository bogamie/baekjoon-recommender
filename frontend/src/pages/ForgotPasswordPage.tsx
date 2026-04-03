import { useState, useRef } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import axios from 'axios'
import { forgotPassword, resetPassword } from '../api/auth'
import styles from './ForgotPasswordPage.module.css'

function passwordRules(pw: string, confirm: string) {
  return {
    length: pw.length >= 8,
    letter: /[a-zA-Z]/.test(pw),
    digit: /[0-9]/.test(pw),
    special: /[^a-zA-Z0-9]/.test(pw),
    match: pw.length > 0 && pw === confirm,
  }
}

export default function ForgotPasswordPage() {
  const navigate = useNavigate()
  const [step, setStep] = useState<1 | 2 | 3>(1)

  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const cooldownRef = useRef<ReturnType<typeof setInterval> | null>(null)

  function startCooldown() {
    setCooldown(60)
    if (cooldownRef.current) clearInterval(cooldownRef.current)
    cooldownRef.current = setInterval(() => {
      setCooldown((prev) => {
        if (prev <= 1) {
          clearInterval(cooldownRef.current!)
          return 0
        }
        return prev - 1
      })
    }, 1000)
  }

  async function handleSendCode() {
    if (!email) return
    setError('')
    setLoading(true)
    try {
      await forgotPassword(email)
      startCooldown()
      setStep(2)
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.data?.message) {
        setError(err.response.data.message)
      } else {
        setError('오류가 발생했습니다. 다시 시도해주세요.')
      }
    } finally {
      setLoading(false)
    }
  }

  async function handleVerifyAndNext() {
    if (code.length !== 6) return
    setError('')
    setStep(3)
  }

  async function handleResend() {
    setError('')
    startCooldown()
    forgotPassword(email).catch(() => {})
  }

  async function handleResetPassword() {
    setError('')
    setLoading(true)
    try {
      await resetPassword(email, code, password)
      navigate('/login', { state: { resetSuccess: true } })
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.data?.message) {
        setError(err.response.data.message)
      } else {
        setError('비밀번호 재설정에 실패했습니다. 다시 시도해주세요.')
      }
    } finally {
      setLoading(false)
    }
  }

  const rules = passwordRules(password, confirmPassword)
  const allRulesMet = Object.values(rules).every(Boolean)

  return (
    <div className={styles.container}>
      <div className={styles.box}>
        <p className={styles.brand}>BaekjoonRec</p>

        {/* Step 1: Email */}
        {step === 1 && (
          <div className={styles.stepWrapper}>
            <h2 className={styles.heading}>비밀번호 찾기</h2>
            <p className={styles.desc}>
              가입한 이메일을 입력하면 인증 코드를 보내드립니다
            </p>
            <div className={styles.fieldGroup}>
              <div className={styles.field}>
                <label className={styles.label}>이메일</label>
                <input
                  className={styles.input}
                  type="email"
                  placeholder="example@email.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSendCode()}
                  autoComplete="email"
                />
              </div>
            </div>
            {error && <p className={styles.errorMsg}>{error}</p>}
            <div className={styles.actions}>
              <button
                className={styles.primaryBtn}
                onClick={handleSendCode}
                disabled={loading || !email}
              >
                {loading ? '발송 중...' : '인증 코드 발송'}
              </button>
            </div>
            <p className={styles.footer}>
              <Link className={styles.link} to="/login">로그인으로 돌아가기</Link>
            </p>
          </div>
        )}

        {/* Step 2: Code */}
        {step === 2 && (
          <div className={styles.stepWrapper}>
            <h2 className={styles.heading}>인증 코드 입력</h2>
            <p className={styles.desc}>
              <strong>{email}</strong>로 발송된 6자리 코드를 입력해주세요
            </p>
            <div className={styles.fieldGroup}>
              <div className={styles.field}>
                <label className={styles.label}>인증 코드</label>
                <input
                  className={styles.input}
                  type="text"
                  placeholder="6자리 코드 입력"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleVerifyAndNext()}
                  maxLength={6}
                />
                <div className={styles.resendRow}>
                  <button
                    className={styles.resendBtn}
                    onClick={handleResend}
                    disabled={cooldown > 0}
                  >
                    재발송
                  </button>
                  {cooldown > 0 && (
                    <span className={styles.countdown}>{cooldown}초 후 재발송 가능</span>
                  )}
                </div>
              </div>
            </div>
            {error && <p className={styles.errorMsg}>{error}</p>}
            <div className={styles.actions}>
              <button className={styles.secondaryBtn} onClick={() => setStep(1)}>
                이전
              </button>
              <button
                className={styles.primaryBtn}
                onClick={handleVerifyAndNext}
                disabled={code.length !== 6}
              >
                다음
              </button>
            </div>
          </div>
        )}

        {/* Step 3: New Password */}
        {step === 3 && (
          <div className={styles.stepWrapper}>
            <h2 className={styles.heading}>새 비밀번호 설정</h2>
            <div className={styles.fieldGroup}>
              <div className={styles.field}>
                <label className={styles.label}>새 비밀번호</label>
                <input
                  className={styles.input}
                  type="password"
                  placeholder="새 비밀번호 입력"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="new-password"
                />
                <div className={styles.checklist}>
                  <span className={`${styles.checkItem} ${rules.length ? styles.met : ''}`}>8자 이상</span>
                  <span className={`${styles.checkItem} ${rules.letter ? styles.met : ''}`}>영문 포함</span>
                  <span className={`${styles.checkItem} ${rules.digit ? styles.met : ''}`}>숫자 포함</span>
                  <span className={`${styles.checkItem} ${rules.special ? styles.met : ''}`}>특수문자 포함</span>
                </div>
              </div>
              <div className={styles.field}>
                <label className={styles.label}>비밀번호 확인</label>
                <input
                  className={styles.input}
                  type="password"
                  placeholder="비밀번호 재입력"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  autoComplete="new-password"
                />
                <div className={styles.checklist}>
                  <span className={`${styles.checkItem} ${rules.match ? styles.met : ''}`}>비밀번호 일치</span>
                </div>
              </div>
            </div>
            {error && <p className={styles.errorMsg}>{error}</p>}
            <div className={styles.actions}>
              <button className={styles.secondaryBtn} onClick={() => setStep(2)}>
                이전
              </button>
              <button
                className={styles.primaryBtn}
                onClick={handleResetPassword}
                disabled={loading || !allRulesMet}
              >
                {loading ? '처리 중...' : '비밀번호 재설정'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
