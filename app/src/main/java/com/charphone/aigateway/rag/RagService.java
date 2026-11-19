package com.charphone.aigateway.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RAG 檢索服務：問題 Embedding → Solr 向量搜尋（knn） → 回傳最相關的文件片段。
 * 使用 Embedded Solr 9 的 DenseVectorField + knn。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MIN_CANDIDATE_K = 20;
    private static final int MAX_CANDIDATE_K = 50;
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final SolrClient solrClient;
    private final EmbeddingClient embeddingClient;
    private final RagDocumentStore documentStore;
    private final RetrievalRanker retrievalRanker;

    public RagService(SolrClient solrClient, EmbeddingClient embeddingClient,
            RagDocumentStore documentStore, RetrievalRanker retrievalRanker) {
        this.solrClient = solrClient;
        this.embeddingClient = embeddingClient;
        this.documentStore = documentStore;
        this.retrievalRanker = retrievalRanker;
    }

    /** 檢索最相關的文件片段。 */
    public Map<String, Object> retrieve(String query, int topK) {
        int effectiveTopK = Math.min(Math.max(1, topK), MAX_CANDIDATE_K);
        int candidateK = Math.min(MAX_CANDIDATE_K,
                Math.max(MIN_CANDIDATE_K, effectiveTopK * CANDIDATE_MULTIPLIER));
        try {
            float[] queryVector = embeddingClient.embed(query);

            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", "{!knn f=embedding topK=" + candidateK + "}"
                    + vectorToString(queryVector));
            params.set("fl", "id,doc_id,filename,chunk_index,text,score");
            params.set("rows", String.valueOf(candidateK));

            QueryResponse response = solrClient.query(params);
            SolrDocumentList results = response.getResults();

            List<Map<String, Object>> hits = new ArrayList<>();
            for (SolrDocument doc : results) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("docId", doc.getFieldValue("doc_id"));
                m.put("filename", doc.getFieldValue("filename"));
                m.put("chunkIndex", doc.getFieldValue("chunk_index"));
                m.put("text", doc.getFieldValue("text"));
                Object scoreVal = doc.getFieldValue("score");
                double score = scoreVal instanceof Number n ? n.doubleValue() : 0.0;
                m.put("denseScore", Math.round(score * 1000) / 1000.0);
                hits.add(m);
            }

            List<Map<String, Object>> ranked = retrievalRanker.rank(query, hits, effectiveTopK);
            for (Map<String, Object> hit : ranked) {
                String docId = (String) hit.get("docId");
                if (docId != null) {
                    documentStore.find(docId).ifPresent(stored -> {
                        hit.put("originalFilename", stored.filename());
                        hit.put("downloadable", true);
                    });
                }
                hit.putIfAbsent("downloadable", false);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("totalChunks", ranked.size());
            result.put("results", ranked);
            return result;

        } catch (SolrServerException | IOException e) {
            log.error("Solr 向量查詢失敗: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("results", List.of());
            result.put("error", e.getMessage());
            return result;
        }
    }

    /** 預設 topK=5 的重載。 */
    public Map<String, Object> retrieve(String query) {
        return retrieve(query, DEFAULT_TOP_K);
    }

    /**
     * 依 Min Score 過濾檢索結果；以原始向量相似度為準，避免 rerank 加分改變語意。
     *
     * @param hits     檢索結果
     * @param minScore 最低 dense score；0 表示不啟用門檻
     * @return 過濾後清單
     */
    public static List<?> filterByMinScore(List<?> hits, double minScore) {
        if (minScore <= 0.0) {
            return hits;
        }
        return hits.stream()
                .filter(hit -> hit instanceof Map<?, ?> map
                        && denseScoreOf(map) >= minScore)
                .toList();
    }

    private static double denseScoreOf(Map<?, ?> hit) {
        Object denseScore = hit.get("denseScore");
        if (denseScore instanceof Number score) {
            return score.doubleValue();
        }
        Object score = hit.get("score");
        return score instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 取得指定文件的所有 chunk，依原始順序排列。
     *
     * @param docId 文件識別碼
     * @return chunk 清單；查詢失敗時回傳空清單
     */
    public List<DocumentChunk> listChunks(String docId) {
        try {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", "doc_id:\"" + ClientUtils.escapeQueryChars(docId) + "\"");
            params.set("sort", "chunk_index asc");
            params.set("rows", "10000");
            params.set("fl", "doc_id,filename,chunk_index,text");

            SolrDocumentList results = solrClient.query(params).getResults();
            List<DocumentChunk> chunks = new ArrayList<>();
            for (SolrDocument doc : results) {
                Object indexValue = doc.getFieldValue("chunk_index");
                int chunkIndex = indexValue instanceof Number number ? number.intValue() : chunks.size();
                chunks.add(new DocumentChunk(chunkIndex,
                        String.valueOf(doc.getFieldValue("filename")),
                        String.valueOf(doc.getFieldValue("text"))));
            }
            return chunks;
        } catch (SolrServerException | IOException e) {
            log.error("Solr 讀取文件 chunk 失敗 docId={}: {}", docId, e.getMessage());
            return List.of();
        }
    }

    /** 文件 chunk 的顯示資料。 */
    public record DocumentChunk(int chunkIndex, String filename, String text) {
    }

    private String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
