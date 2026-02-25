# 🚀 Smart API Gateway

<div align="center">

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Spring WebFlux](https://img.shields.io/badge/Spring%20WebFlux-Reactive-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Resilience4j](https://img.shields.io/badge/Resilience4j-Circuit%20Breaker-F7B731?style=for-the-badge)
![Docker](https://img.shields.io/badge/Docker-Enabled-2496ED?style=for-the-badge&logo=docker&logoColor=white)

**The high-performance, fully reactive API Gateway**  
Centralized routing, zero-trust security, distributed rate-limiting and fault tolerance.

</div>

---

## 📖 Overview

Smart API Gateway is the centralized "front door" for multiple backend microservices (e.g., **SmartCity**, **Blogger**, **CMS**). Built on **Spring WebFlux** and **Spring Cloud Gateway**, it is designed around a non-blocking, reactive paradigm — capable of handling high-concurrency workloads without traditional thread-per-request overhead.

---

## ✨ Key Features

| Feature | Description |
|---|---|
| 🗺️ **Dynamic Routing** | Onboard new microservices instantly via `application.yml` — no code changes needed |
| 🔐 **Zero-Trust Security** | Centralized auth supporting both **Asymmetric (RSA)** and **Symmetric (HMAC) JWTs**, plus Redis-backed session cookies |
| 🪣 **Distributed Rate Limiting** | Custom **Token Bucket** algorithm via atomic Lua script in Redis — race-condition-free |
| ⚡ **Fault Tolerance** | Integrated **Resilience4j** Circuit Breakers and TimeLimiters to prevent cascading failures |
| 🛡️ **Standardized Error Handling** | Global `ErrorWebExceptionHandler` guarantees a strict JSON error contract for all failure scenarios (401, 403, 429, 503, 504) |
| 🐳 **Docker-First** | Full Docker Compose setup — spin up the gateway and Redis with a single command |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Language & Runtime** | Java 21 |
| **Framework** | Spring Boot 3.4.1, Spring Cloud Gateway 2024.0.1 |
| **Reactive Engine** | Project Reactor (WebFlux, Netty) |
| **Security** | Spring Security, JJWT (Java JWT Library) |
| **Fault Tolerance** | Spring Cloud Circuit Breaker (Resilience4j) |
| **Caching & State** | Redis (Lettuce Reactive Client), Lua Scripting |
| **DevOps** | Docker, Docker Compose, Maven Wrapper |

---

## 🏗️ Architecture & Request Flow

Every inbound request passes through a strict, ordered pipeline before reaching a backend service.

```
Client Request
      │
      ▼
┌─────────────────────┐
│    CorsWebFilter    │  ← Handles pre-flight OPTIONS requests
└─────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────┐
│               GlobalGatewayFilter                   │
│  1. Project Resolver  → Matches URI to backend      │
│  2. CSRF Check        → Validates X-XSRF-TOKEN      │
│  3. AuthService       → Verifies JWT / Session      │
│  4. RateLimiter       → Executes Lua script (Redis) │
└─────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────┐
│    ProxyService     │  ← Appends X-User-Id, X-User-Role headers
│    (WebClient)      │  ← Forwards request to upstream service
└─────────────────────┘
      │
      ▼
┌─────────────────────┐
│   Resilience4j      │  ← Monitors response; opens circuit on failure
│  Circuit Breaker    │    threshold (e.g., 50% failure rate → 503)
└─────────────────────┘
      │
      ▼
  Backend Service
```

---

## 🚀 Quick Start

The easiest way to run the gateway is with Docker Compose.

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine + Compose)
- Java 21
- Git

---

### Step 1 — Clone the Repository

```bash
git clone https://github.com/yourusername/smart-api-gateway.git
cd smart-api-gateway
```

### Step 2 — Configure Environment Variables

The gateway reads JWT secrets and Redis config from a `.env` file. Create one in the project root:

```env
# ========== JWT Secrets ==========
SMART_CITY_JWT_PUBLIC_KEY=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAl9Q9...
BLOGGER_JWT_SECRET=QDRlY1JmVWpYbjJyNXU4eC9BP0QoRytrYnBQZFNnVmtZcA==

# ========== Redis ==========
REDIS_HOST=redis
REDIS_PORT=6379

# ========== Environment ==========
SPRING_PROFILES_ACTIVE=local
```

---

### Step 3 — Build & Run

```bash
docker-compose up -d --build
```

This starts both the **Smart API Gateway** and a **Redis** container.

---

### Step 4 — Verify the Deployment

**Check application logs:**
```bash
docker logs -f smart-gateway
```

**Check health via Actuator:**
```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP","groups":["liveness","readiness"]}
```

---

### Step 5 — Test Routing & Rate Limiting

**Test a public route (SmartCity anonymous path):**
```bash
curl -v http://localhost:8080/smartcity/actuator/health
```

**Test the Token Bucket Rate Limiter:**
```bash
for i in {1..10}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/smartcity/actuator/health
done
```

Expected: `200` for the first 3 to 5 requests, then `429 Too Many Requests`.

---

### Stopping the Services

```bash
docker-compose down
```

---

## 📄 Standardized Error Contract

Whether a request fails at the gateway level (e.g., rate limit exceeded) or a backend service crashes, the client always receives the same structured JSON error response:

```json
{
  "timestamp": "2026-02-25T10:00:00",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Too many requests. Please slow down.",
  "path": "/smartcity/api/data"
}
```

| Status | Scenario |
|---|---|
| `401 Unauthorized` | Missing or invalid JWT / session |
| `403 Forbidden` | Valid token, insufficient permissions or failed CSRF check |
| `429 Too Many Requests` | Token bucket exhausted |
| `503 Service Unavailable` | Circuit breaker open (upstream failures exceed threshold) |
| `504 Gateway Timeout` | Backend did not respond within the configured timeout |

---

## 👨‍💻 Author

**Sobhit Raghav**  
Software Engineer passionate about Distributed Systems, Reactive Programming, and Cloud Architecture.

[![LinkedIn](https://img.shields.io/badge/LinkedIn-Connect-0A66C2?style=flat&logo=linkedin)](https://www.linkedin.com/in/sobhit-raghav/)
[![GitHub](https://img.shields.io/badge/GitHub-Follow-181717?style=flat&logo=github)](https://github.com/sobhit1)
