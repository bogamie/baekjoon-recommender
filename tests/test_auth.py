"""
Auth endpoint tests — uses an in-memory SQLite DB so tests are isolated.
"""
from __future__ import annotations

import os
import uuid
import pytest

os.environ["MOCK_MODE"] = "true"
os.environ["DATA_DIR"]  = "./data"   # ensure data dir exists for test DB

from fastapi.testclient import TestClient
from app.main import app
from app.db import init_db

# Initialise tables before any request
init_db()
client = TestClient(app)

# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def registered_user():
    """Complete two-step registration once and return token + user."""
    uniq = uuid.uuid4().hex[:8]
    username = f"testuser_{uniq}"
    email = f"auth_{uniq}@test.com"

    start = client.post("/api/v1/auth/register/start", json={
        "username": username,
        "email": email,
        "password": "password123",
    })
    assert start.status_code == 202
    reg_data = start.json()
    req_id = reg_data.get("request_id")
    code = reg_data.get("verification_code_debug")
    assert req_id
    assert code

    complete = client.post("/api/v1/auth/register/complete", json={
        "request_id": req_id,
        "verification_code": code,
    })
    assert complete.status_code == 201
    return complete.json()


@pytest.fixture
def auth_headers(registered_user):
    return {"Authorization": f"Bearer {registered_user['access_token']}"}


# ── Register ──────────────────────────────────────────────────────────────────

class TestRegister:
    def test_register_new_user(self):
        uniq = uuid.uuid4().hex[:8]
        resp = client.post("/api/v1/auth/register/start", json={
            "username": f"newuser_{uniq}",
            "email":    f"newuser_{uniq}@test.com",
            "password": "securePass1",
        })
        assert resp.status_code == 202
        data = resp.json()
        assert data["request_id"]
        assert data["verification_code_debug"]
        assert data["email_masked"].endswith("@test.com")

    def test_register_with_boj_handle(self):
        uniq = uuid.uuid4().hex[:8]
        start = client.post("/api/v1/auth/register/start", json={
            "username": f"withhandle_{uniq}",
            "email":    f"withhandle_{uniq}@test.com",
            "password": "securePass1",
            "boj_handle": "tourist",
        })
        assert start.status_code == 202
        req_id = start.json()["request_id"]
        code = start.json()["verification_code_debug"]
        complete = client.post("/api/v1/auth/register/complete", json={
            "request_id": req_id,
            "verification_code": code,
        })
        assert complete.status_code == 201
        assert complete.json()["user"]["boj_handle"] == "tourist"
        assert complete.json()["user"]["email_verified"] is True

    def test_register_duplicate_username(self, registered_user):
        existing_username = registered_user["user"]["username"]
        resp = client.post("/api/v1/auth/register/start", json={
            "username": existing_username,
            "email":    "other@test.com",
            "password": "password123",
        })
        assert resp.status_code == 400
        assert "unable to create account" in resp.json()["detail"].lower()

    def test_register_duplicate_email(self, registered_user):
        existing_email = registered_user["user"]["email"]
        resp = client.post("/api/v1/auth/register/start", json={
            "username": "another_unique789",
            "email":    existing_email,
            "password": "password123",
        })
        assert resp.status_code == 400

    def test_register_short_password(self):
        resp = client.post("/api/v1/auth/register/start", json={
            "username": "shortpwuser",
            "email":    "short@test.com",
            "password": "123",
        })
        assert resp.status_code == 422  # pydantic validation error

    def test_register_invalid_email(self):
        resp = client.post("/api/v1/auth/register/start", json={
            "username": "bademail_user",
            "email":    "not-an-email",
            "password": "password123",
        })
        assert resp.status_code == 422


# ── Login ─────────────────────────────────────────────────────────────────────

class TestLogin:
    def test_login_success(self):
        uniq = uuid.uuid4().hex[:8]
        username = f"loginok_{uniq}"
        email = f"loginok_{uniq}@test.com"
        start = client.post("/api/v1/auth/register/start", json={
            "username": username,
            "email": email,
            "password": "password123",
        })
        assert start.status_code == 202
        req_id = start.json()["request_id"]
        code = start.json()["verification_code_debug"]
        complete = client.post("/api/v1/auth/register/complete", json={
            "request_id": req_id,
            "verification_code": code,
        })
        assert complete.status_code == 201

        resp = client.post("/api/v1/auth/login", json={
            "username": username,
            "password": "password123",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "access_token" in data
        assert data["token_type"] == "bearer"

    def test_login_wrong_password(self):
        uniq = uuid.uuid4().hex[:8]
        username = f"wrongpw_{uniq}"
        email = f"wrongpw_{uniq}@test.com"
        start = client.post("/api/v1/auth/register/start", json={
            "username": username,
            "email": email,
            "password": "password123",
        })
        assert start.status_code == 202
        req_id = start.json()["request_id"]
        code = start.json()["verification_code_debug"]
        complete = client.post("/api/v1/auth/register/complete", json={
            "request_id": req_id,
            "verification_code": code,
        })
        assert complete.status_code == 201

        resp = client.post("/api/v1/auth/login", json={
            "username": username,
            "password": "wrongpass",
        })
        assert resp.status_code == 401

    def test_login_nonexistent_user(self):
        resp = client.post("/api/v1/auth/login", json={
            "username": "nobody_xyz",
            "password": "password123",
        })
        assert resp.status_code == 401

    def test_login_before_registration_complete(self):
        uniq = uuid.uuid4().hex[:8]
        username = f"unverified_{uniq}"
        start = client.post("/api/v1/auth/register/start", json={
            "username": username,
            "email": f"unverified_{uniq}@test.com",
            "password": "password123",
        })
        assert start.status_code == 202

        login = client.post("/api/v1/auth/login", json={
            "username": username,
            "password": "password123",
        })
        assert login.status_code == 401

    def test_account_lock_after_repeated_failures(self):
        uniq = uuid.uuid4().hex[:8]
        username = f"lockme_{uniq}"
        start = client.post("/api/v1/auth/register/start", json={
            "username": username,
            "email": f"lockme_{uniq}@test.com",
            "password": "password123",
        })
        assert start.status_code == 202
        req_id = start.json()["request_id"]
        code = start.json()["verification_code_debug"]
        complete = client.post("/api/v1/auth/register/complete", json={
            "request_id": req_id,
            "verification_code": code,
        })
        assert complete.status_code == 201

        for _ in range(5):
            r = client.post("/api/v1/auth/login", json={
                "username": username,
                "password": "wrongpass",
            })
            assert r.status_code == 401

        locked = client.post("/api/v1/auth/login", json={
            "username": username,
            "password": "password123",
        })
        assert locked.status_code == 423


# ── /me ───────────────────────────────────────────────────────────────────────

class TestMe:
    def test_me_authenticated(self, auth_headers):
        resp = client.get("/api/v1/auth/me", headers=auth_headers)
        assert resp.status_code == 200
        assert resp.json()["username"].startswith("testuser_")

    def test_me_no_token(self):
        resp = client.get("/api/v1/auth/me")
        assert resp.status_code in (401, 403)  # HTTPBearer returns 403 for missing creds

    def test_me_bad_token(self):
        resp = client.get("/api/v1/auth/me", headers={"Authorization": "Bearer bad.token.here"})
        assert resp.status_code == 401


# ── BOJ handle ────────────────────────────────────────────────────────────────

class TestBojHandle:
    def test_update_handle(self, auth_headers):
        resp = client.patch(
            "/api/v1/auth/boj-handle",
            json={"boj_handle": "bogamie"},
            headers=auth_headers,
        )
        assert resp.status_code == 200
        assert resp.json()["boj_handle"] == "bogamie"

    def test_recommend_uses_authenticated_handle(self, auth_headers):
        # After setting handle, /recommend should use it without a query param
        resp = client.get("/api/v1/recommend", headers=auth_headers)
        assert resp.status_code == 200
        assert resp.json()["handle"] == "bogamie"

    def test_recommend_explicit_handle_overrides(self, auth_headers):
        # Explicit query param should override stored handle
        resp = client.get("/api/v1/recommend?handle=bogamie", headers=auth_headers)
        assert resp.status_code == 200
