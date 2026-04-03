import { useState, useRef, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import styles from './UserMenu.module.css'

export default function UserMenu() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  function handleLogout() {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className={styles.wrapper} ref={ref}>
      <button className={styles.trigger} onClick={() => setOpen((v) => !v)}>
        {user?.username}
      </button>
      {open && (
        <div className={styles.dropdown}>
          <button
            className={styles.menuItem}
            onClick={() => {
              setOpen(false)
              navigate('/settings')
            }}
          >
            설정
          </button>
          <div className={styles.divider} />
          <button className={styles.menuItem} onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      )}
    </div>
  )
}
