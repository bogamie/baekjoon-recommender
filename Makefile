.PHONY: up down build restart logs ps \
       db backend frontend \
       clean reset nuke \
       dev dev-back dev-front

# ── 전체 스택 ──────────────────────────────────────────────
up:                ## 전체 서비스 시작
	docker compose up -d

down:              ## 전체 서비스 중지
	docker compose down

build:             ## 이미지 빌드 후 시작
	docker compose up -d --build

restart:           ## 전체 재시작 (리빌드 포함)
	docker compose down && docker compose up -d --build

logs:              ## 전체 로그 (follow)
	docker compose logs -f

ps:                ## 컨테이너 상태 확인
	docker compose ps

# ── 개별 서비스 ────────────────────────────────────────────
db:                ## PostgreSQL만 시작
	docker compose up -d postgres

backend:           ## 백엔드만 빌드 + 시작
	docker compose up -d --build backend

frontend:          ## 프론트엔드만 빌드 + 시작
	docker compose up -d --build frontend

# ── 로컬 개발 (컨테이너 없이) ──────────────────────────────
dev: db             ## DB 띄우고 백엔드 + 프론트 로컬 실행
	@echo "== DB started on :5432 =="
	@echo "Run 'make dev-back' and 'make dev-front' in separate terminals"

dev-back:          ## 백엔드 로컬 실행 (DB 필요)
	cd backend && ./gradlew bootRun

dev-front:         ## 프론트 로컬 실행 (dev proxy → :8080)
	cd frontend && npm run dev

# ── 정리 ───────────────────────────────────────────────────
clean:             ## 컨테이너 중지 + 제거 (볼륨 유지)
	docker compose down --remove-orphans

reset:             ## 컨테이너 + 볼륨 제거 (DB 초기화)
	docker compose down -v --remove-orphans

nuke:              ## 컨테이너 + 볼륨 + 이미지 모두 제거
	docker compose down -v --remove-orphans --rmi local

# ── 도움말 ─────────────────────────────────────────────────
help:              ## 사용 가능한 명령어 목록
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*##"}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
