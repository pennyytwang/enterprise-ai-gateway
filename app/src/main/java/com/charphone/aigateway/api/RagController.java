package com.charphone.aigateway.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import com.charphone.aigateway.audit.AppUserContext;
import com.charphone.aigateway.rag.IngestionService;
import com.charphone.aigateway.rag.RagAskService;
import com.charphone.aigateway.rag.RagDocumentStore;
import com.charphone.aigateway.rag.RagService;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.security.ApiKeyFilter;

/**
 * RAG API：文件上傳/檢索/管理。
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final IngestionService ingestionService;
    private final RagService ragService;
    private final RagAskService ragAskService;
    private final RagDocumentStore documentStore;

    public RagController(IngestionService ingestionService, RagService ragService, RagAskService ragAskService,
            RagDocumentStore documentStore) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
        this.ragAskService = ragAskService;
        this.documentStore = documentStore;
    }

    /** 上傳並擷取文件（PDF/DOCX/TXT/MD）。 */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingest(@RequestPart("file") MultipartFile file,
            @RequestPart(value = "originalFilename", required = false) String originalFilename) throws Exception {
        return ingestionService.ingest(file, originalFilename);
    }

    /** 列出已擷取的文件。 */
    @GetMapping("/documents")
    public Map<String, Object> listDocuments() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", ingestionService.listDocuments());
        return result;
    }

    /** 下載已保存的原始檔；舊文件若未保存原始檔則回 404。 */
    @GetMapping("/documents/{docId}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable String docId) {
        Optional<RagDocumentStore.StoredDocument> found = documentStore.find(docId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RagDocumentStore.StoredDocument document = found.get();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(document.contentType()))
                .contentLength(document.size())
                .body(new FileSystemResource(document.path()));
    }

    /**
     * 以 HTML 檢視文件擷取文字，並將指定 chunk 以黃色標註。
     *
     * @param docId 文件識別碼
     * @param chunk 要標註的 chunk index；-1 表示不標註
     * @return 文件檢視頁；文件不存在時回 404
     */
    @GetMapping(value = "/documents/{docId}/viewer", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> viewDocument(@PathVariable String docId,
            @RequestParam(defaultValue = "-1") int chunk) {
        Optional<RagDocumentStore.StoredDocument> stored = documentStore.find(docId);
        List<RagService.DocumentChunk> chunks = ragService.listChunks(docId);
        if (stored.isEmpty() && chunks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String filename = stored.map(RagDocumentStore.StoredDocument::filename)
                .orElseGet(() -> chunks.get(0).filename());
        String html = buildViewerHtml(filename, chunks, chunk, stored.isPresent());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header("Cache-Control", "no-store")
                .body(html);
    }

    private String buildViewerHtml(String filename, List<RagService.DocumentChunk> chunks,
            int selectedChunk, boolean downloadable) {
        String safeFilename = HtmlUtils.htmlEscape(filename);
        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html><html lang=\"zh-Hant\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>").append(safeFilename).append(" - 文件檢視</title>")
                .append("<style>")
                .append("*{box-sizing:border-box}body{margin:0;background:#f4f6fb;color:#1a1a2e;")
                .append("font-family:\"Microsoft JhengHei\",\"Segoe UI\",sans-serif;line-height:1.8}")
                .append(".shell{max-width:1000px;margin:0 auto;padding:24px 16px 60px}")
                .append(".topbar{position:sticky;top:0;z-index:2;background:rgba(244,246,251,.95);")
                .append("backdrop-filter:blur(8px);border-bottom:1px solid #e2e8f0;padding:14px 0 12px;margin-bottom:18px}")
                .append(".topbar h1{font-size:18px;margin:0 0 4px;word-break:break-all}")
                .append(".meta{font-size:12px;color:#64748b}.actions{margin-top:10px;display:flex;gap:8px;flex-wrap:wrap}")
                .append(".btn{display:inline-block;padding:7px 12px;border-radius:8px;text-decoration:none;")
                .append("font-size:13px;font-weight:700;border:1px solid #99f6e4;background:#f0fdfa;color:#0f766e}")
                .append(".card{background:#fff;border-radius:12px;box-shadow:0 1px 4px rgba(0,0,0,.06);padding:24px 28px}")
                .append(".notice{background:#eff6ff;border:1px solid #bfdbfe;color:#1e40af;border-radius:8px;")
                .append("padding:10px 12px;font-size:13px;margin-bottom:16px}")
                .append(".chunk{padding:12px 14px;margin:0 -6px 12px;border-radius:8px;border:1px solid transparent}")
                .append(".chunk .chunk-meta{font-size:11px;font-weight:700;color:#94a3b8;margin-bottom:3px}")
                .append(".chunk .text{white-space:pre-wrap;word-break:break-word;font-size:14px}")
                .append(".chunk.highlight{background:#fff3a3;border-color:#facc15;box-shadow:0 0 0 2px rgba(250,204,21,.18)}")
                .append(".chunk.highlight .chunk-meta{color:#a16207}")
                .append(".empty{color:#94a3b8;text-align:center;padding:30px}")
                .append("@media(max-width:767px){.card{padding:16px}.shell{padding:16px 10px 40px}.topbar h1{font-size:16px}}")
                .append("</style></head><body><div class=\"shell\"><header class=\"topbar\"><h1>")
                .append(safeFilename).append("</h1><div class=\"meta\">文件檢視 · 共 ")
                .append(chunks.size()).append(" 個段落</div><div class=\"actions\">");
        if (downloadable) {
            html.append("<a class=\"btn\" href=\"./download\">下載原始檔</a>");
        }
        html.append("</div></header><main class=\"card\">")
                .append("<div class=\"notice\">此頁顯示由原始文件擷取的文字；PDF/DOCX 版面可能與原檔不同。")
                .append("黃色區塊為本次 RAG 回答引用的段落。</div>");

        if (chunks.isEmpty()) {
            html.append("<div class=\"empty\">此文件尚無可檢視的擷取文字。</div>");
        } else {
            for (RagService.DocumentChunk item : chunks) {
                boolean highlight = item.chunkIndex() == selectedChunk;
                html.append("<section class=\"chunk").append(highlight ? " highlight" : "")
                        .append("\" id=\"chunk-").append(item.chunkIndex()).append("\">")
                        .append("<div class=\"chunk-meta\">段落 ").append(item.chunkIndex() + 1)
                        .append(" · chunk ").append(item.chunkIndex()).append("</div>")
                        .append("<div class=\"text\">")
                        .append(HtmlUtils.htmlEscape(item.text()))
                        .append("</div></section>");
            }
        }
        html.append("</main></div>");
        if (selectedChunk >= 0) {
            html.append("<script>try{document.getElementById('chunk-").append(selectedChunk)
                    .append("')?.scrollIntoView({block:'center'});}catch(e){}</script>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    /** 刪除指定文件。 */
    @DeleteMapping("/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String docId) {
        int deleted = ingestionService.deleteByDocId(docId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("deletedChunks", deleted);
        return result;
    }

    /** 檢索知識庫。 */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        return ragService.retrieve(query, topK);
    }

    /** 知識庫問答：檢索相關片段後由 LLM 直接給出答案。 */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestParam String query,
            @RequestParam(defaultValue = "0") int topK, HttpServletRequest httpRequest) {
        ApiClient client = (ApiClient) httpRequest.getAttribute(ApiKeyFilter.ATTR_CLIENT);
        return ragAskService.ask(query, topK, client, AppUserContext.from(httpRequest));
    }
}
