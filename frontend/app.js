'use strict';

/* =====================================================================
   State
   ===================================================================== */
const state = {
  token:      localStorage.getItem('ps_token'),
  user:       null,
  analysis:   null,
  recs:       null,
  loading:    false,
  error:      null,
  // 'login' | 'register-1' | 'register-2' | 'link' | 'dashboard'
  view:       'login',
  editHandle: false,
  registerFlow: {
    requestId:   null,
    emailMasked: null,
  },
  authConfig: {
    captchaRequired: false,
    captchaSiteKey:  null,
  },
};

const _captchaWidgets = {};

/* =====================================================================
   API layer
   ===================================================================== */
const BASE = '/api/v1';

async function req(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (state.token) headers['Authorization'] = `Bearer ${state.token}`;
  Object.assign(headers, opts.headers || {});

  const res = await fetch(BASE + path, {
    method:  opts.method || 'GET',
    headers,
    body:    opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });

  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    const raw = data.detail || `HTTP ${res.status}`;
    throw new Error(normalizeErrorMessage(raw));
  }
  return res.json();
}

function normalizeErrorMessage(msg) {
  const text = String(msg || '').trim();

  if (text.includes('No candidate problems found in tier range')) {
    return '현재 추천 난이도 구간에서 후보 문제를 찾지 못했습니다.';
  }
  if (text === 'CAPTCHA verification failed') {
    return '보안 인증(CAPTCHA)에 실패했습니다. 다시 시도해 주세요.';
  }
  if (text === 'Incorrect username or password') {
    return '아이디 또는 비밀번호가 올바르지 않습니다.';
  }
  if (text === 'Unable to create account with provided credentials') {
    return '입력한 정보로 계정을 생성할 수 없습니다.';
  }
  if (text === 'Invalid or expired verification code') {
    return '인증 코드가 올바르지 않거나 만료되었습니다.';
  }
  if (text.startsWith('HTTP ')) {
    return `요청 처리 중 오류가 발생했습니다. (${text})`;
  }
  return text;
}

const api = {
  authConfig:   ()  => req('/auth/public-config'),
  registerStart:    (d) => req('/auth/register/start',    { method: 'POST', body: d }),
  registerComplete: (d) => req('/auth/register/complete', { method: 'POST', body: d }),
  login:        (d) => req('/auth/login',     { method: 'POST',  body: d }),
  me:           ()  => req('/auth/me'),
  updateHandle: (h) => req('/auth/boj-handle', { method: 'PATCH', body: { boj_handle: h } }),
  analysis:     (h) => req(`/analysis?handle=${encodeURIComponent(h)}`),
  recommend:    (h, n = 10) => req(`/recommend?handle=${encodeURIComponent(h)}&n=${n}`),
};

/* =====================================================================
   Utilities
   ===================================================================== */
function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function tierInfo(t) {
  if (!t) return { label: '미등급', color: '#64748b', bg: '#f1f5f9' };

  const TIERS = [
    null,
    ['Bronze', 6], ['Bronze', 6], ['Bronze', 6], ['Bronze', 6], ['Bronze', 6],
    ['Silver', 11], ['Silver', 11], ['Silver', 11], ['Silver', 11], ['Silver', 11],
    ['Gold', 16], ['Gold', 16], ['Gold', 16], ['Gold', 16], ['Gold', 16],
    ['Platinum', 21], ['Platinum', 21], ['Platinum', 21], ['Platinum', 21], ['Platinum', 21],
    ['Diamond', 26], ['Diamond', 26], ['Diamond', 26], ['Diamond', 26], ['Diamond', 26],
  ];
  const COLORS = {
    Bronze:   { color: '#92400e', bg: '#fef3c7' },
    Silver:   { color: '#374151', bg: '#f3f4f6' },
    Gold:     { color: '#78350f', bg: '#fef9c3' },
    Platinum: { color: '#0e7490', bg: '#cffafe' },
    Diamond:  { color: '#5b21b6', bg: '#ede9fe' },
  };
  if (t < 1 || t >= TIERS.length) return { label: `티어 ${t}`, color: '#64748b', bg: '#f1f5f9' };
  const [name, inv] = TIERS[t];
  return { label: `${name} ${inv - t}`, ...COLORS[name] };
}

function fmtDays(d) {
  if (d < 1)   return '오늘';
  if (d < 7)   return `${d}일 전`;
  if (d < 30)  return `${Math.floor(d / 7)}주 전`;
  if (d < 365) return `${Math.floor(d / 30)}개월 전`;
  return `${Math.floor(d / 365)}년 전`;
}

/* =====================================================================
   HTML Templates
   ===================================================================== */

/* ── Auth shared wrapper ──────────────────────────────────────────── */
function _authCard(title, subtitle, body) {
  return `
    <div class="auth-wrap">
      <div class="auth-card">
        <div class="auth-logo">
          <span class="logo-mark">⚡</span>
          <h1>${title}</h1>
          <p>${subtitle}</p>
        </div>
        ${state.error ? `<div class="alert alert-error">${esc(state.error)}</div>` : ''}
        ${body}
      </div>
    </div>`;
}

function _regSteps(step) {
  const s1 = step === 1 ? 'active' : 'done';
  const s2 = step === 2 ? 'active' : '';
  const lineClass = step > 1 ? 'done' : '';
  return `
    <div class="reg-steps">
      <div class="reg-step ${s1}">
        <div class="reg-step-dot">${step > 1 ? '✓' : '1'}</div>
        <span class="reg-step-lbl">정보 입력</span>
      </div>
      <div class="reg-step-line ${lineClass}"></div>
      <div class="reg-step ${s2}">
        <div class="reg-step-dot">2</div>
        <span class="reg-step-lbl">이메일 인증</span>
      </div>
    </div>`;
}

/* ── Login ────────────────────────────────────────────────────────── */
function tplLogin() {
  return _authCard('PS 추천기', 'BOJ 맞춤 문제 추천 시스템', `
    <form id="frm-login">
      <div class="field">
        <label>아이디</label>
        <input type="text" name="username" placeholder="username"
               required autocomplete="username" autofocus />
      </div>
      <div class="field">
        <label>비밀번호</label>
        <input type="password" name="password" placeholder="••••••••"
               required autocomplete="current-password" />
      </div>
      ${captchaBlock('captcha-login')}
      <button type="submit" class="btn btn-primary btn-full" id="btn-login">
        <span class="btn-label">로그인</span>
      </button>
    </form>
    <div class="auth-switch">
      계정이 없으신가요?
      <button class="link-btn" id="btn-go-register">회원가입 →</button>
    </div>`);
}

/* ── Register step 1 — 정보 입력 ──────────────────────────────────── */
function tplRegisterStep1() {
  return _authCard('회원가입', '새 계정을 만들어 주세요', `
    ${_regSteps(1)}
    <form id="frm-register-start">
      <div class="field">
        <label>아이디</label>
        <input type="text" name="username" placeholder="3–30자, 영문/숫자/_/-"
               required autocomplete="username" minlength="3" autofocus />
      </div>
      <div class="field">
        <label>이메일</label>
        <input type="email" name="email" placeholder="you@example.com"
               required autocomplete="email" />
        <span class="field-hint">이 주소로 인증 코드가 발송됩니다</span>
      </div>
      <div class="field">
        <label>비밀번호</label>
        <input type="password" name="password" placeholder="영문+숫자 포함 10자 이상"
               required autocomplete="new-password" minlength="10" />
      </div>
      <div class="field">
        <label>백준 핸들 <span class="opt">(선택)</span></label>
        <input type="text" name="boj_handle" placeholder="solved.ac 핸들" autocomplete="off" />
        <span class="field-hint">
          <a href="https://solved.ac" target="_blank" rel="noopener">solved.ac</a> 프로필의 핸들 · 나중에 연동해도 됩니다
        </span>
      </div>
      ${captchaBlock('captcha-register-start')}
      <button type="submit" class="btn btn-primary btn-full" id="btn-register-start">
        <span class="btn-label">다음: 이메일 인증 →</span>
      </button>
    </form>
    <div class="auth-switch">
      이미 계정이 있으신가요?
      <button class="link-btn" id="btn-go-login">로그인 →</button>
    </div>`);
}

/* ── Register step 2 — 이메일 인증 ────────────────────────────────── */
function tplRegisterStep2() {
  return _authCard('이메일 인증', '받은 코드를 입력해 주세요', `
    ${_regSteps(2)}
    <p style="font-size:13px;color:var(--text-2);margin-bottom:20px;line-height:1.6">
      <strong>${esc(state.registerFlow.emailMasked || '')}</strong> 으로<br>
      6자리 인증 코드를 발송했습니다.
    </p>
    <form id="frm-register-complete">
      <div class="field">
        <label>인증 코드</label>
        <input type="text" name="verification_code"
               placeholder="000000"
               required minlength="6" maxlength="6"
               inputmode="numeric" pattern="[0-9]{6}"
               autocomplete="one-time-code" autofocus
               style="font-size:24px;letter-spacing:8px;text-align:center" />
        <span class="field-hint">코드는 ${state.registerFlow.ttlMin || 10}분 후 만료됩니다</span>
      </div>
      <button type="submit" class="btn btn-primary btn-full" id="btn-register-complete">
        <span class="btn-label">가입 완료</span>
      </button>
    </form>
    <div style="text-align:center">
      <button class="back-link" id="btn-register-back">← 이전 단계로</button>
    </div>`);
}

function captchaBlock(containerId) {
  if (!isCaptchaEnabled()) return '';
  return `<div class="field"><div id="${containerId}" class="captcha-box"></div></div>`;
}

/* ── Handle-link page (after register with no handle) ─────────────── */
function tplLink() {
  return `
    <div class="auth-wrap">
      <div class="auth-card">
        <div class="auth-logo">
          <span class="logo-mark">🔗</span>
          <h1>백준 계정 연동</h1>
          <p>solved.ac 핸들을 연결해 주세요</p>
        </div>
        ${state.error ? `<div class="alert alert-error">${esc(state.error)}</div>` : ''}
        <form id="frm-link">
          <div class="field">
            <label>solved.ac 핸들</label>
            <input type="text" name="boj_handle" placeholder="예: tourist" required autocomplete="off" />
            <span class="field-hint">
              <a href="https://solved.ac" target="_blank" rel="noopener">solved.ac</a> 프로필 URL의 마지막 부분입니다
            </span>
          </div>
          <button type="submit" class="btn btn-primary btn-full">
            <span class="btn-label">연동하기</span>
          </button>
        </form>
        <hr class="divider" />
        <button class="btn btn-ghost btn-full" id="btn-logout-link">로그아웃</button>
      </div>
    </div>`;
}

/* ── Dashboard ────────────────────────────────────────────────────── */
function tplDashboard() {
  const { user, analysis, recs } = state;
  const profile = analysis?.profile;

  return `
    ${tplHeader()}
    <main class="dash-main">
      ${state.loading && !profile ? tplSkeletonHero() : ''}
      ${profile  ? tplHero(profile, analysis) : ''}
      ${analysis?.inactivity_warning ? tplInactivityBanner(analysis) : ''}
      ${state.editHandle ? tplHandleEdit() : ''}
      ${profile  ? tplAnalysis(analysis) : ''}
      ${tplRecsSection(recs)}
    </main>`;
}

function tplHeader() {
  const { user } = state;
  const handle = user?.boj_handle;
  return `
    <header class="header">
      <div class="header-inner">
        <a href="/" class="header-brand">
          <span class="mark">⚡</span>
          <span>PS 추천기</span>
        </a>
        <div class="header-right">
          <span class="header-username">${esc(user?.username || '')}</span>
          ${handle
            ? `<a href="https://solved.ac/profile/${encodeURIComponent(handle)}"
                  target="_blank" rel="noopener" class="header-handle">@${esc(handle)}</a>`
            : ''}
          <button class="btn btn-ghost btn-sm" id="btn-edit-handle">
            ${handle ? '핸들 변경' : '핸들 연동'}
          </button>
          <button class="btn btn-ghost btn-sm" id="btn-logout">로그아웃</button>
        </div>
      </div>
    </header>`;
}

function tplHero(p, analysis) {
  const { label, color, bg } = tierInfo(p.tier);
  const d = p.days_since_last_solve;
  const dColor = d > 180 ? '#dc2626' : d > 60 ? '#d97706' : '#16a34a';
  return `
    <div class="hero-card">
      <div class="hero-tier-badge" style="color:${color};background:${bg}">${label}</div>
      <div class="hero-stats">
        <div class="stat">
          <div class="stat-val">${p.solved_count.toLocaleString()}</div>
          <div class="stat-label">푼 문제</div>
        </div>
        <div class="stat">
          <div class="stat-val">${p.rating}</div>
          <div class="stat-label">레이팅</div>
        </div>
        <div class="stat">
          <div class="stat-val" style="color:${dColor}">${fmtDays(d)}</div>
          <div class="stat-label">마지막 풀이</div>
        </div>
        <div class="stat">
          <div class="stat-val" style="font-size:18px">T${p.recommended_tier_min}–T${p.recommended_tier_max}</div>
          <div class="stat-label">추천 난이도</div>
        </div>
      </div>
    </div>`;
}

function tplInactivityBanner(analysis) {
  return `
    <div class="alert alert-warning">
      <strong>⚠ 장기 휴식 감지</strong>
      <span>${esc(analysis.inactivity_message)}</span>
      <span class="badge-pill">${esc(analysis.skill_degradation_estimate)}</span>
    </div>`;
}

function tplHandleEdit() {
  const current = state.user?.boj_handle || '';
  return `
    <div class="handle-edit">
      <p class="section-title" style="margin-bottom:14px">백준 핸들 변경</p>
      ${state.error ? `<div class="alert alert-error">${esc(state.error)}</div>` : ''}
      <form id="frm-handle-edit">
        <div class="field">
          <label>새 핸들</label>
          <div class="handle-edit-row">
            <input type="text" name="boj_handle" value="${esc(current)}" placeholder="solved.ac 핸들" required autocomplete="off" />
            <button type="submit" class="btn btn-primary btn-sm">변경</button>
            <button type="button" class="btn btn-secondary btn-sm" id="btn-cancel-handle">취소</button>
          </div>
        </div>
      </form>
    </div>`;
}

function tplAnalysis(analysis) {
  const p = analysis.profile;
  const focus  = analysis.focus_recommendation?.slice(0, 6) || [];
  const unseen = p.unseen_tags?.slice(0, 5) || [];

  return `
    <div class="section">
      <div class="section-header">
        <h2 class="section-title">분석 결과</h2>
      </div>
      <p class="section-sub">${esc(analysis.summary)}</p>
      ${focus.length ? `
        <div class="tag-row">
          <span class="tag-row-label">집중 추천</span>
          ${focus.map(t => `<span class="tag tag-focus">${esc(t)}</span>`).join('')}
        </div>` : ''}
      ${unseen.length ? `
        <div class="tag-row">
          <span class="tag-row-label">미경험 태그</span>
          ${unseen.map(t => `<span class="tag tag-unseen">${esc(t)}</span>`).join('')}
        </div>` : ''}
    </div>`;
}

function tplRecsSection(recs) {
  return `
    <div class="section">
      <div class="section-header flex-between">
        <h2 class="section-title">추천 문제</h2>
        <button class="btn btn-secondary btn-sm" id="btn-refresh" ${state.loading ? 'disabled' : ''}>
          ${state.loading ? '로딩 중…' : '↺ 새로고침'}
        </button>
      </div>
      ${state.error && !state.loading ? `<div class="alert alert-error" style="margin-top:12px">${esc(state.error)}</div>` : ''}
      ${!recs && state.loading ? tplSkeletonRecs() : ''}
      ${recs ? `<div class="rec-list">${recs.recommendations.map(tplRecCard).join('')}</div>` : ''}
      ${!recs && !state.loading ? `<p style="color:var(--text-3);font-size:13px;margin-top:12px">핸들을 연동하면 추천 문제를 확인할 수 있습니다.</p>` : ''}
    </div>`;
}

function tplRecCard(rec) {
  const { problem: p, score, reason, expected_outcome, rank } = rec;
  const { label, color, bg } = tierInfo(p.tier);
  const pct  = Math.round(score * 100);
  const sColor = score >= 0.70 ? '#16a34a' : score >= 0.50 ? '#d97706' : '#94a3b8';

  return `
    <div class="rec-card">
      <div class="rec-rank">#${rank}</div>
      <div class="rec-body">
        <div class="rec-head">
          <span class="tier-badge" style="color:${color};background:${bg}">${label}</span>
          <a href="${esc(p.boj_url)}" target="_blank" rel="noopener" class="rec-title">${esc(p.title)}</a>
          <span class="rec-id">BOJ ${p.problem_id}</span>
        </div>
        <div class="rec-tags">
          ${p.tags.slice(0, 4).map(t => `<span class="tag">${esc(t)}</span>`).join('')}
        </div>
        <p class="rec-reason">${esc(reason)}</p>
        <p class="rec-outcome">${esc(expected_outcome)}</p>
        <div class="rec-foot">
          <div class="score-track">
            <div class="score-fill" style="width:${pct}%;background:${sColor}"></div>
          </div>
          <span class="score-num" style="color:${sColor}">${score.toFixed(3)}</span>
          <a href="${esc(p.boj_url)}" target="_blank" rel="noopener" class="btn btn-accent btn-xs">풀기 →</a>
        </div>
      </div>
    </div>`;
}

/* ── Skeleton ─────────────────────────────────────────────────────── */
function tplSkeletonHero() {
  return `<div class="skeleton sk-hero"></div>`;
}

function tplSkeletonRecs() {
  return Array(3).fill(0).map(() =>
    `<div class="skeleton sk-card"></div>`
  ).join('');
}

/* =====================================================================
   Event binding
   ===================================================================== */
function bindLogin() {
  document.getElementById('btn-go-register')?.addEventListener('click', () => {
    state.view = 'register-1';
    state.error = null;
    render();
  });

  document.getElementById('frm-login')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const d = formData(e.target);
    await withBtn('btn-login', '로그인 중…', async () => {
      d.captcha_token = getCaptchaToken('captcha-login');
      const res = await api.login(d);
      saveSession(res);
      await loadDashboard();
    });
  });

  renderCaptchaWidgets();
}

function bindRegister() {
  // ── step 1 ──
  document.getElementById('btn-go-login')?.addEventListener('click', () => {
    state.view  = 'login';
    state.error = null;
    render();
  });

  document.getElementById('frm-register-start')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const d = formData(e.target);
    if (!d.boj_handle) delete d.boj_handle;
    await withBtn('btn-register-start', '코드 발송 중…', async () => {
      d.captcha_token = getCaptchaToken('captcha-register-start');
      const res = await api.registerStart(d);
      state.registerFlow = {
        requestId:   res.request_id,
        emailMasked: res.email_masked,
        ttlMin:      Math.round((res.expires_in_seconds || 600) / 60),
      };
      // Dev mode: show the code as a dismissible hint
      state.error = res.verification_code_debug
        ? `개발용 인증 코드: ${res.verification_code_debug}`
        : null;
      state.view = 'register-2';
      render();
    });
  });

  // ── step 2 ──
  document.getElementById('btn-register-back')?.addEventListener('click', () => {
    state.view  = 'register-1';
    state.error = null;
    render();
  });

  document.getElementById('frm-register-complete')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const d = formData(e.target);
    await withBtn('btn-register-complete', '인증 확인 중…', async () => {
      const res = await api.registerComplete({
        request_id:        state.registerFlow.requestId,
        verification_code: d.verification_code,
      });
      saveSession(res);
      state.registerFlow = { requestId: null, emailMasked: null, ttlMin: null };
      state.view = 'dashboard';
      await loadDashboard();
    });
  });

  renderCaptchaWidgets();
}

function bindLink() {
  document.getElementById('frm-link')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const d = formData(e.target);
    await withBtn(null, null, async () => {
      const user = await api.updateHandle(d.boj_handle);
      state.user = user;
      state.error = null;
      await loadDashboard();
    });
  });
  document.getElementById('btn-logout-link')?.addEventListener('click', logout);
}

function bindDashboard() {
  document.getElementById('btn-logout')?.addEventListener('click', logout);

  document.getElementById('btn-edit-handle')?.addEventListener('click', () => {
    state.editHandle = !state.editHandle;
    state.error = null;
    render();
  });

  document.getElementById('btn-cancel-handle')?.addEventListener('click', () => {
    state.editHandle = false;
    state.error = null;
    render();
  });

  document.getElementById('frm-handle-edit')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const d = formData(e.target);
    await withBtn(null, null, async () => {
      const user = await api.updateHandle(d.boj_handle);
      state.user = user;
      state.editHandle = false;
      state.analysis = null;
      state.recs = null;
      state.error = null;
      await loadDashboard();
    });
  });

  document.getElementById('btn-refresh')?.addEventListener('click', async () => {
    state.recs = null;
    state.error = null;
    render();
    await loadRecs();
  });
}

/* =====================================================================
   Async data loading
   ===================================================================== */
async function loadDashboard() {
  render();
  if (!state.user?.boj_handle) return;

  state.loading = true;
  render();
  try {
    const [analysis, recs] = await Promise.all([
      api.analysis(state.user.boj_handle),
      api.recommend(state.user.boj_handle, 10),
    ]);
    state.analysis = analysis;
    state.recs     = recs;
    state.error    = null;
  } catch (err) {
    state.error = normalizeErrorMessage(err.message);
  } finally {
    state.loading = false;
    render();
  }
}

async function loadRecs() {
  if (!state.user?.boj_handle) return;
  state.loading = true;
  render();
  try {
    state.recs  = await api.recommend(state.user.boj_handle, 10);
    state.error = null;
  } catch (err) {
    state.error = normalizeErrorMessage(err.message);
  } finally {
    state.loading = false;
    render();
  }
}

/* =====================================================================
   Helpers
   ===================================================================== */
function formData(form) {
  return Object.fromEntries(new FormData(form));
}

function isCaptchaEnabled() {
  return !!(state.authConfig.captchaRequired && state.authConfig.captchaSiteKey);
}

function renderCaptchaWidgets() {
  if (!isCaptchaEnabled()) return;
  if (!window.grecaptcha || typeof window.grecaptcha.render !== 'function') return;

  ['captcha-login', 'captcha-register-start'].forEach((id) => {
    const el = document.getElementById(id);
    if (!el || _captchaWidgets[id] !== undefined) return;
    _captchaWidgets[id] = window.grecaptcha.render(id, {
      sitekey: state.authConfig.captchaSiteKey,
    });
  });
}

function getCaptchaToken(containerId) {
  if (!isCaptchaEnabled()) return null;  // Return null explicitly when disabled
  const widgetId = _captchaWidgets[containerId];
  if (widgetId === undefined || !window.grecaptcha) {
    throw new Error('보안 인증(CAPTCHA) 준비 중입니다. 잠시 후 다시 시도해 주세요.');
  }
  const token = window.grecaptcha.getResponse(widgetId);
  if (!token) {
    throw new Error('CAPTCHA 인증을 완료해 주세요.');
  }
  return token;
}

function saveSession(res) {
  state.token = res.access_token;
  state.user  = res.user;
  localStorage.setItem('ps_token', state.token);
  state.error = null;
}

async function withBtn(btnId, loadingLabel, fn) {
  const btn = btnId ? document.getElementById(btnId) : null;
  const lbl = btn?.querySelector('.btn-label');
  const orig = lbl?.textContent;
  if (btn)  btn.disabled = true;
  if (lbl && loadingLabel) lbl.textContent = loadingLabel;
  state.error = null;

  try {
    await fn();
  } catch (err) {
    state.error = normalizeErrorMessage(err.message);
    render();
  } finally {
    if (btn)  btn.disabled = false;
    if (lbl && orig) lbl.textContent = orig;
  }
}

function logout() {
  Object.assign(state, {
    token: null, user: null, analysis: null,
    recs: null, error: null, editHandle: false,
    view: 'login',
    registerFlow: { requestId: null, emailMasked: null, ttlMin: null },
  });
  localStorage.removeItem('ps_token');
  render();
}

/* =====================================================================
   Core render / router
   ===================================================================== */
function render() {
  const root = document.getElementById('app');

  // Not authenticated → auth views
  if (!state.token || !state.user) {
    if (state.view === 'register-2') {
      root.innerHTML = tplRegisterStep2();
      bindRegister();
    } else if (state.view === 'register-1') {
      root.innerHTML = tplRegisterStep1();
      bindRegister();
    } else {
      root.innerHTML = tplLogin();
      bindLogin();
    }
    return;
  }

  // Authenticated but no BOJ handle → link page
  if (!state.user.boj_handle) {
    root.innerHTML = tplLink();
    bindLink();
    return;
  }

  // Full dashboard
  root.innerHTML = tplDashboard();
  bindDashboard();
}

/* =====================================================================
   Init — check existing token
   ===================================================================== */
async function init() {
  try {
    const cfg = await api.authConfig();
    state.authConfig.captchaRequired = !!cfg.captcha_required;
    state.authConfig.captchaSiteKey = cfg.captcha_site_key || null;
  } catch {
    state.authConfig.captchaRequired = false;
    state.authConfig.captchaSiteKey = null;
  }

  if (state.token) {
    try {
      state.user = await api.me();
      state.view = 'dashboard';
      render();                 // show skeleton immediately
      await loadDashboard();
      return;
    } catch {
      state.token = null;
      localStorage.removeItem('ps_token');
    }
  }
  state.view = 'login';
  render();
}

init();
