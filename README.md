# Enterprise AI Gateway

集中式 AI 閘道：讓內部系統透過單一 API 呼叫多家 LLM，並在同一層處理 API Key、稽核、限流、Agent 工具呼叫與 RAG 知識庫。

> 公開展示版。範例資料與金鑰皆為虛構/示範用途，不含真實營運資料或雲端憑證。

## 功能

- 多供應商 LLM：Gemini、OpenAI 相容端點（OpenRouter / xAI / Groq 等）
- 統一 `/api/v1/chat` 與 SSE 串流 `/api/v1/chat/stream`
- Agent Tool Calling：可查詢訂單、客戶、生產、出貨、應收、庫存等 ERP 資料
- RAG：PDF / Word / TXT 擷取、切塊、Embedding、Embedded Solr 檢索
- API Key 認證（只存 SHA-256 雜湊）、每日/每分鐘限流
- 稽核日誌：prompt/response、token 用量、工具呼叫、CharPhone 使用者身分
- 管理 API 與靜態管理頁 `/admin.html`
- Kubernetes manifest 與 Docker Compose 兩種本機啟動方式

## 架構

```text
CharPhone / 內部系統
        │  X-API-Key
        ▼
  AI Gateway (Spring Boot WAR / Tomcat)
        ├─ API Key / Rate Limit Filter
        ├─ Chat / Agent / RAG Controllers
        ├─ Model Router ──► Gemini / OpenAI 相容供應商
        ├─ Tool Registry ──► mydb（ERP 查詢）
        └─ Audit / Settings ──► aigateway（MySQL）
                     │
                     └─ Embedded Solr（RAG 向量檢索）
```

詳細設計見 [docs/architecture.md](docs/architecture.md)。

## 技術棧

- Java 17、Spring Boot 3、WAR
- Spring MVC / WebClient、JDBC
- MySQL 8.0
- Apache Solr 9（Embedded）
- Docker Compose、Kubernetes manifests

## 快速開始（Docker Compose）

```bash
cd ops/deploy
cp .env.example .env
# 編輯 .env，至少填入 GEMINI_API_KEY（或改用 openai-compat）
docker compose up -d --build
```

- Gateway：<http://localhost:8081>
- 健康檢查：<http://localhost:8081/api/v1/health>
- 管理頁：<http://localhost:8081/admin.html>
- 示範 API Key：`demo-api-key-charphone`

`data.sql` 只儲存此金鑰的 SHA-256 雜湊；明文僅出現在本文件與 CharPhone 的 demo 設定。

### 測試 Chat

```bash
curl -s http://localhost:8081/api/v1/chat \
  -H "X-API-Key: demo-api-key-charphone" \
  -H "Content-Type: application/json" \
  -d '{"userMessage":"請用一句話介紹自己","stream":false}'
```

未設定 LLM 金鑰時，服務仍可啟動、健康檢查會回 `UP`，但 Chat/Agent/RAG 會回傳供應商錯誤。

### 測試 Agent（需要 LLM 金鑰）

```bash
curl -s http://localhost:8081/api/v1/agent/run \
  -H "X-API-Key: demo-api-key-charphone" \
  -H "Content-Type: application/json" \
  -d '{"userMessage":"目前有哪些訂單還沒出貨？"}'
```

### 測試 RAG

```bash
curl -s -X POST "http://localhost:8081/api/v1/rag/ask?query=報價流程" \
  -H "X-API-Key: demo-api-key-charphone"
```

## 與 CharPhone 串接

兩個公開 repo 的 Compose 預設都使用 `charphone_demo` network，Gateway 服務名稱為 `ai-gateway`。CharPhone 的 AI 頁面會代理到 `http://ai-gateway:8080`，並使用同一個示範 API Key。

## Kubernetes

```bash
docker build -t ai-gateway:local -f app/Dockerfile app
kubectl apply -k ops/k8s
kubectl port-forward svc/ai-gateway -n ai-gateway 8081:8080
```

詳見 [ops/k8s/README.md](ops/k8s/README.md)。

## 目錄結構

```text
app/
  src/main/java/com/charphone/aigateway/
    api/         REST controllers
    security/    API Key、限流
    provider/    LLM 供應商與 Model Router
    agent/       Tool calling 迴圈
    tools/       ERP 查詢工具
    rag/         擷取、切塊、Embedding、Solr 檢索
    audit/       稽核與用量
    config/      資料源、Solr、WebClient
  src/main/resources/
    schema.sql / data.sql
    solrhome/    Embedded Solr 設定
    static/      管理頁與技術測試頁
ops/
  deploy/        Docker Compose
  demo/          示範 MySQL 初始化
  k8s/           Kubernetes manifests
docs/            架構文件
```

## 安全說明

- API Key 僅存 SHA-256 雜湊，明文只在建立時回傳一次。
- 管理 API 需要 `ADMIN` 角色。
- 稽核日誌寫入前會遮罩敏感欄位。
- 可啟用每日/每分鐘限流。
- 本 repo 的密碼與金鑰皆為示範值，正式環境請全部替換。

## 授權

[MIT](LICENSE)
