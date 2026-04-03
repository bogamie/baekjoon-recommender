-- BaekjoonRec Database Schema

-- 사용자
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN DEFAULT FALSE,
    solvedac_handle VARCHAR(50),
    theme VARCHAR(10) DEFAULT 'light',
    include_foreign BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 이메일 인증 코드
CREATE TABLE email_verification (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    code VARCHAR(6) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    verified BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Refresh Token
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(512) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 문제 데이터 (solved.ac에서 캐싱)
CREATE TABLE problems (
    id INTEGER PRIMARY KEY,
    title VARCHAR(500),
    level INTEGER,
    solved_count INTEGER,
    accepted_rate DOUBLE PRECISION,
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 문제-태그 매핑
CREATE TABLE problem_tags (
    problem_id INTEGER REFERENCES problems(id) ON DELETE CASCADE,
    tag_key VARCHAR(100),
    tag_name VARCHAR(200),
    PRIMARY KEY (problem_id, tag_key)
);

-- 사용자 풀이 기록 (solved.ac에서 동기화)
CREATE TABLE user_solved (
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    problem_id INTEGER REFERENCES problems(id) ON DELETE CASCADE,
    solved_at TIMESTAMP,
    PRIMARY KEY (user_id, problem_id)
);

-- 사용자 분석 캐시
CREATE TABLE user_analysis (
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE PRIMARY KEY,
    global_status VARCHAR(30),
    gap_days INTEGER,
    active_days_90 INTEGER,
    total_solved INTEGER,
    tier INTEGER,
    rating INTEGER,
    analyzed_at TIMESTAMP DEFAULT NOW()
);

-- 카테고리별 분석 캐시
CREATE TABLE user_tag_stats (
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    tag_key VARCHAR(100),
    solved_count INTEGER,
    avg_level DOUBLE PRECISION,
    max_level INTEGER,
    last_solved_at TIMESTAMP,
    proficiency VARCHAR(20),
    is_dormant BOOLEAN,
    effective_pool_size INTEGER DEFAULT 0,
    near_avg_count INTEGER DEFAULT 0,
    tag_name VARCHAR(200),
    PRIMARY KEY (user_id, tag_key)
);

-- 추천 이력
CREATE TABLE recommendation_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    problem_id INTEGER NOT NULL,
    slot_type VARCHAR(20) NOT NULL,
    target_tag VARCHAR(100),
    recommended_at TIMESTAMP DEFAULT NOW(),
    status VARCHAR(20) DEFAULT 'PENDING',
    skipped_at TIMESTAMP
);

-- 인덱스
CREATE INDEX idx_user_solved_user_id ON user_solved(user_id);
CREATE INDEX idx_user_solved_problem_id ON user_solved(problem_id);
CREATE INDEX idx_problem_tags_tag_key ON problem_tags(tag_key);
CREATE INDEX idx_recommendation_history_user_id ON recommendation_history(user_id);
CREATE INDEX idx_email_verification_email ON email_verification(email);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
