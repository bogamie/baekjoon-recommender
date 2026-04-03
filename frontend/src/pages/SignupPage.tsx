import { Fragment, useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  checkEmail,
  checkUsername,
  sendCode,
  verifyCode,
  signup,
} from '../api/auth'
import styles from './SignupPage.module.css'

type FieldStatus = 'idle' | 'checking' | 'available' | 'taken' | 'error'

interface FormData {
  email: string
  username: string
  password: string
  confirmPassword: string
  solvedacHandle: string
}

function StepIndicator({ current }: { current: number }) {
  return (
    <div className={styles.stepIndicator}>
      {[1, 2, 3, 4].map((n, i) => (
        <Fragment key={n}>
          {i > 0 && (
            <span className={styles.stepSep}>—</span>
          )}
          <span
            className={`${styles.stepItem} ${current === n ? styles.active : ''}`}
          >
            {n}
          </span>
        </Fragment>
      ))}
    </div>
  )
}

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return debounced
}

function passwordRules(pw: string, confirm: string) {
  return {
    length: pw.length >= 8,
    letter: /[a-zA-Z]/.test(pw),
    digit: /[0-9]/.test(pw),
    special: /[^a-zA-Z0-9]/.test(pw),
    match: pw.length > 0 && pw === confirm,
  }
}

export default function SignupPage() {
  const navigate = useNavigate()
  const [step, setStep] = useState(1)
  const [form, setForm] = useState<FormData>({
    email: '',
    username: '',
    password: '',
    confirmPassword: '',
    solvedacHandle: '',
  })

  // Step 1 availability state
  const [emailStatus, setEmailStatus] = useState<FieldStatus>('idle')
  const [usernameStatus, setUsernameStatus] = useState<FieldStatus>('idle')

  const debouncedEmail = useDebounce(form.email, 500)
  const debouncedUsername = useDebounce(form.username, 500)

  // Step 3 state
  const [codeInput, setCodeInput] = useState('')
  const [codeSent, setCodeSent] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const cooldownRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // General error/loading
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  // Email availability check
  useEffect(() => {
    if (!debouncedEmail || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(debouncedEmail)) {
      setEmailStatus('idle')
      return
    }
    setEmailStatus('checking')
    checkEmail(debouncedEmail)
      .then((res) => setEmailStatus(res.data.available ? 'available' : 'taken'))
      .catch(() => setEmailStatus('error'))
  }, [debouncedEmail])

  // Username availability check
  useEffect(() => {
    if (!debouncedUsername || debouncedUsername.length < 2) {
      setUsernameStatus('idle')
      return
    }
    setUsernameStatus('checking')
    checkUsername(debouncedUsername)
      .then((res) => setUsernameStatus(res.data.available ? 'available' : 'taken'))
      .catch(() => setUsernameStatus('error'))
  }, [debouncedUsername])

  // Auto-send code when entering step 3
  useEffect(() => {
    if (step === 3 && !codeSent) {
      doSendCode()
    }
  }, [step]) // eslint-disable-line react-hooks/exhaustive-deps

  function updateField(field: keyof FormData, value: string) {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

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

  function doSendCode() {
    setCodeSent(true)
    startCooldown()
    sendCode(form.email).catch(() => {})
  }

  const rules = passwordRules(form.password, form.confirmPassword)
  const allRulesMet = Object.values(rules).every(Boolean)

  const step1Valid = emailStatus === 'available' && usernameStatus === 'available'

  async function handleStep3Verify() {
    if (!codeInput) return
    setError('')
    setLoading(true)
    try {
      await verifyCode(form.email, codeInput)
      setStep(4)
    } catch {
      setError('인증 코드가 올바르지 않습니다')
    } finally {
      setLoading(false)
    }
  }

  const handleComplete = useCallback(
    async (skip: boolean) => {
      setError('')
      setLoading(true)
      try {
        await signup({
          email: form.email,
          username: form.username,
          password: form.password,
          solvedacHandle: skip ? undefined : form.solvedacHandle || undefined,
        })
        navigate('/login', { state: { signupSuccess: true } })
      } catch {
        setError('회원가입 중 오류가 발생했습니다')
      } finally {
        setLoading(false)
      }
    },
    [form, navigate],
  )

  function statusText(status: FieldStatus, type: 'email' | 'username') {
    if (status === 'checking') return '확인 중...'
    if (status === 'available') return '사용 가능'
    if (status === 'taken') return type === 'email' ? '이미 사용 중인 이메일입니다' : '이미 사용 중인 아이디입니다'
    return ''
  }

  function statusClass(status: FieldStatus) {
    if (status === 'available') return styles.available
    if (status === 'taken') return styles.taken
    return ''
  }

  return (
    <div className={styles.container}>
      <div className={styles.box}>
        <p className={styles.brand}>BaekjoonRec</p>
        <StepIndicator current={step} />

        {/* STEP 1 */}
        {step === 1 && (
          <div className={styles.stepWrapper} key="step1">
            <div className={styles.fieldGroup}>
              <div className={styles.field}>
                <label className={styles.label}>이메일</label>
                <input
                  className={styles.input}
                  type="email"
                  placeholder="example@email.com"
                  value={form.email}
                  onChange={(e) => updateField('email', e.target.value)}
                  autoComplete="email"
                />
                <span className={`${styles.fieldStatus} ${statusClass(emailStatus)}`}>
                  {statusText(emailStatus, 'email')}
                </span>
              </div>
              <div className={styles.field}>
                <label className={styles.label}>아이디</label>
                <input
                  className={styles.input}
                  type="text"
                  placeholder="사용할 아이디"
                  value={form.username}
                  onChange={(e) => updateField('username', e.target.value)}
                  autoComplete="username"
                />
                <span className={`${styles.fieldStatus} ${statusClass(usernameStatus)}`}>
                  {statusText(usernameStatus, 'username')}
                </span>
              </div>
            </div>
            <div className={styles.actions}>
              <button
                className={styles.primaryBtn}
                onClick={() => setStep(2)}
                disabled={!step1Valid}
              >
                다음
              </button>
            </div>
          </div>
        )}

        {/* STEP 2 */}
        {step === 2 && (
          <div className={styles.stepWrapper} key="step2">
            <div className={styles.fieldGroup}>
              <div className={styles.field}>
                <label className={styles.label}>비밀번호</label>
                <input
                  className={styles.input}
                  type="password"
                  placeholder="비밀번호 입력"
                  value={form.password}
                  onChange={(e) => updateField('password', e.target.value)}
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
                  value={form.confirmPassword}
                  onChange={(e) => updateField('confirmPassword', e.target.value)}
                  autoComplete="new-password"
                />
                <div className={styles.checklist}>
                  <span className={`${styles.checkItem} ${rules.match ? styles.met : ''}`}>비밀번호 일치</span>
                </div>
              </div>
            </div>
            <div className={styles.actions}>
              <button className={styles.secondaryBtn} onClick={() => setStep(1)}>
                이전
              </button>
              <button
                className={styles.primaryBtn}
                onClick={() => setStep(3)}
                disabled={!allRulesMet}
              >
                다음
              </button>
            </div>
          </div>
        )}

        {/* STEP 3 */}
        {step === 3 && (
          <div className={styles.stepWrapper} key="step3">
            <p className={styles.codeInfo}>
              인증 코드가 <strong>{form.email}</strong>로 발송되었습니다
            </p>
            <div className={styles.fieldGroup}>
              <div className={styles.field}>
                <label className={styles.label}>인증 코드</label>
                <input
                  className={styles.input}
                  type="text"
                  placeholder="6자리 코드 입력"
                  value={codeInput}
                  onChange={(e) => setCodeInput(e.target.value)}
                  maxLength={6}
                />
                <div className={styles.resendRow}>
                  <button
                    className={styles.resendBtn}
                    onClick={doSendCode}
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
              <button className={styles.secondaryBtn} onClick={() => setStep(2)}>
                이전
              </button>
              <button
                className={styles.primaryBtn}
                onClick={handleStep3Verify}
                disabled={codeInput.length !== 6 || loading}
              >
                {loading ? '확인 중...' : '확인'}
              </button>
            </div>
          </div>
        )}

        {/* STEP 4 */}
        {step === 4 && (
          <div className={styles.stepWrapper} key="step4">
            <div className={styles.fieldGroup}>
              <div className={styles.field}>
                <label className={styles.label}>solved.ac 아이디 (선택)</label>
                <input
                  className={styles.input}
                  type="text"
                  placeholder="solved.ac 핸들"
                  value={form.solvedacHandle}
                  onChange={(e) => updateField('solvedacHandle', e.target.value)}
                />
                <p className={styles.optionalNote}>
                  입력하면 풀이 현황과 추천을 바로 확인할 수 있습니다
                </p>
              </div>
            </div>
            {error && <p className={styles.errorMsg}>{error}</p>}
            <div className={styles.actions}>
              <button
                className={styles.skipBtn}
                onClick={() => handleComplete(true)}
                disabled={loading}
              >
                건너뛰기
              </button>
              <button
                className={styles.primaryBtn}
                onClick={() => handleComplete(false)}
                disabled={loading}
              >
                {loading ? '처리 중...' : '완료'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
