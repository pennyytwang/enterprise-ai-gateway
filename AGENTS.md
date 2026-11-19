# AGENTS.md — Enterprise AI Gateway 公開展示版開發指引

> 這是公開展示版（demo），不是正式營運版。任何 AI 助手或開發者動手前先讀本文件。
> 正式營運版在私有 repo `pennyytwang/enterprise-ai-gateway-ops`；本 repo 不可用於正式部署。

## 1. Repo 分工

| 角色 | 本機路徑 | GitHub | 用途 |
|---|---|---|---|
| 公開展示版 | `C:\project\public\enterprise-ai-gateway` | `pennyytwang/enterprise-ai-gateway` | 虛構 demo 資料、可公開的程式與設定 |
| 私有正式版 | `C:\project\enterprise-ai-gateway` | `pennyytwang/enterprise-ai-gateway-ops` | 正式 API Key、正式 DB、部署腳本、DEV-GUIDE、payloads |

- 兩者是**獨立 git history**，沒有共同祖先。
- 禁止 merge、cherry-pick、`git push --mirror`，或把私有 repo 的 `.git` 複製到本 repo。
- 正式功能先在私有版完成，再手動同步去識別化後的程式碼到本 repo。
- 本 repo 的 `AGENTS.md` 是公開文件，不可寫入私有 IP、SSH 路徑、真實客戶名或任何金鑰。

## 2. 絕對禁止

不可加入或追蹤：

- `.env`、`cookies.txt`
- `backup_*.sql`、`migration_backup_*`
- `nginx/`
- `docs/DEV-GUIDE.md`
- `docs/MIGRATION-PLAN.md`
- `docs/K8S-LOCAL-PLAN.md`
- `docs/K8S-WALKTHROUGH.md`
- `docs/HOW-TO-TEST-AND-INTRO.md`
- `docs/phase0-*.md`
- `docs/tech-solr-rag-analysis.md`
- `ops/payloads/`、`ops/devbox/`
- `ops/deploy/redeploy-container.sh`、`ops/deploy/switch-model.sh`
- `geminiapi-key.txt`、`openrouter-apikey.txt`
- 真實 API Key、資料庫密碼、雲端憑證、客戶資料
- 任何 production DB dump

若不確定某檔案能不能公開，先不要加；問維護者。

## 3. Demo 設定

- Demo API Key：`demo-api-key-charphone`
- `app/src/main/resources/data.sql` 只存此 key 的 SHA-256：`a5b564c4108a2d8e29aa3c6c1961de7c9f6a6e88a7173c243d19f8aa86772c11`
- `application.yml` 不可恢復 `../geminiapi-key.txt` 或任何本機 key file 預設值。
- `ops/deploy/.env.example` 是唯一環境變數範本；`.env` 已 gitignore。
- `ops/demo/mysql-init/` 建立 `aigateway` 與 `mydb` 的 demo 帳號與 schema。
- 公開版 Compose 固定使用 `charphone_demo` network；Gateway 服務名稱固定 `ai-gateway`，對外 `127.0.0.1:8081`。
- `ops/k8s/` 只放 demo 值，不可放正式 Secret。

## 4. 日常開發與 Push

```powershell
cd C:\project\public\enterprise-ai-gateway
cd app
mvn test
cd ..
git status
git diff
powershell -ExecutionPolicy Bypass -File scripts/check-public-safety.ps1
git add -A
git commit -m "fix: 說明修正內容"
git push origin main
```

Docker Compose 驗證：

```powershell
cd ops/deploy
docker compose config
docker compose up -d --build
```

若 Docker build 因 DNS 無法解析 Maven Central，先確認 Docker Desktop 網路；不要因此改壞 Dockerfile。Maven 測試是主要建置驗證。

## 5. 從私有版同步 SOP

1. 在 `C:\project\enterprise-ai-gateway` 完成正式版修改與測試。
2. 只把程式碼與必要設定複製到 `C:\project\public\enterprise-ai-gateway`。
3. 對照第 2 節禁止清單，刪除不該公開的內容。
4. 在公開版重新跑 `mvn test` 與安全檢查腳本。
5. `git diff --cached` 人工複查，確認沒有真實 IP、公司名、統編、金鑰。
6. 分開 commit/push；不要合併 private history。

## 6. 驗證清單

- [ ] `cd app; mvn test` 通過
- [ ] `powershell -ExecutionPolicy Bypass -File scripts/check-public-safety.ps1` 通過
- [ ] `cd ops/deploy; docker compose config` 通過
- [ ] `git status` 沒有未預期檔案
- [ ] `git diff --cached` 沒有敏感資料
- [ ] 截圖只使用虛構資料，且未拍到網址列、書籤、通知

## 7. 常見陷阱

- `application.yml` 不可恢復本機 key file 預設值；公開版只用 `.env` / 環境變數。
- `data.sql` 每次啟動都會執行；demo key 以 `WHERE NOT EXISTS` 避免重複插入。
- `ops/k8s/03-secret.yaml` 只放 demo 值；正式 Secret 不可進 repo。
- `ops/demo/mysql-init/01-users.sh` 只建立 demo DB 帳號；不可放正式密碼。
- `docs/architecture.md` 與 `docs/system-architecture.html` 是去識別化版本，修改時不可加回正式主機資訊。
- 公開 repo 的 commit 會永久留在 GitHub；發現敏感資料要立即輪換金鑰並清理，不是只刪檔。
- Docker Desktop 的 DNS/exec 可能不穩；先用本機 Maven 測試，再處理 Docker。
