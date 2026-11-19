# 本機 Kubernetes 示範

此目錄提供一組可直接套用的 K8s manifest，用來在本機叢集跑起 Gateway + MySQL + Solr PVC。這不是正式部署範本。

## 檔案

| 檔案 | 內容 |
|------|------|
| `01-namespace.yaml` | namespace `ai-gateway` |
| `02-configmap.yaml` | 非機密環境值 |
| `03-secret.yaml` | 資料庫密碼與 LLM API Key（示範值） |
| `04-solr-pvc.yaml` | Solr 資料 PVC |
| `05-gateway-deployment.yaml` | Gateway Deployment 與 probes |
| `06-gateway-service.yaml` | ClusterIP Service |
| `07-mysql-*.yaml` | MySQL Deployment / Service / PVC |
| `mysql-init/01-init.sql` | 首次啟動建立資料庫、帳號與虛構 ERP 資料 |
| `kustomization.yaml` | 一次套用全部資源 |

## 快速開始

```bash
docker build -t ai-gateway:local -f app/Dockerfile app
kubectl apply -k ops/k8s
kubectl port-forward svc/ai-gateway -n ai-gateway 8081:8080
curl http://localhost:8081/api/v1/health
```

收尾：

```bash
kubectl delete -k ops/k8s
```

## 注意

- `03-secret.yaml` 的資料庫密碼必須與 `mysql-init/01-init.sql` 一致。
- 要實際呼叫 LLM 時，請在 `03-secret.yaml` 填入 `gemini-key` 或 `openrouter-key`。
- `05-gateway-deployment.yaml` 使用本機 image `ai-gateway:local`，正式環境請改為 registry image。
- 若叢集有多個節點，可能需要加上 `nodeSelector` 或先把 image 推到 registry。
