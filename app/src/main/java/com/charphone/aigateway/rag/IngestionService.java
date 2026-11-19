package com.charphone.aigateway.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * RAG 擷取管線：讀取文件 → 切塊 → Embedding → 存入 Solr。
 * 支援 PDF、Word (.docx)、TXT/Markdown。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final SolrClient solrClient;
    private final EmbeddingClient embeddingClient;
    private final RagDocumentStore documentStore;
    private final RagTextCleaner textCleaner;
    private final RagChunker chunker;

    public IngestionService(SolrClient solrClient, EmbeddingClient embeddingClient,
            RagDocumentStore documentStore, RagTextCleaner textCleaner, RagChunker chunker) {
        this.solrClient = solrClient;
        this.embeddingClient = embeddingClient;
        this.documentStore = documentStore;
        this.textCleaner = textCleaner;
        this.chunker = chunker;
    }

    /** 上傳並擷取文件。回傳 ingestion 結果摘要。 */
    public Map<String, Object> ingest(MultipartFile file) throws IOException {
        return ingest(file, null);
    }

    /**
     * 上傳並擷取文件，並保留瀏覽器送來的原始檔名。
     *
     * @param file                  上傳檔案（multipart 檔名可能已被消毒）
     * @param originalFilenameField 原始檔名欄位；空白時退回 multipart 檔名
     * @return ingestion 結果摘要
     * @throws IOException 讀取或寫入失敗
     */
    public Map<String, Object> ingest(MultipartFile file, String originalFilenameField) throws IOException {
        String multipartFilename = file.getOriginalFilename();
        String originalFilename = originalFilenameField != null && !originalFilenameField.isBlank()
                ? originalFilenameField.trim() : multipartFilename;
        String filename = RagDocumentStore.normalizeFilename(originalFilename);
        byte[] content = file.getBytes();
        String rawText = extractText(content, originalFilename);
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("無法從檔案中擷取文字內容");
        }
        String text = textCleaner.clean(rawText);
        if (text.isBlank()) {
            throw new IllegalArgumentException("文件清理後無有效文字內容");
        }

        String docId = UUID.randomUUID().toString();
        List<String> chunks = chunker.chunk(text);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文件擷取文字後無有效內容");
        }
        log.info("文件 '{}' 擷取完成：原始 {} 字，清理後 {} 字 → {} 塊",
                filename, rawText.length(), text.length(), chunks.size());

        List<float[]> embeddings = embeddingClient.embedBatch(chunks);

        List<SolrInputDocument> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("id", docId + "_" + i);
            doc.addField("doc_id", docId);
            doc.addField("filename", filename);
            doc.addField("chunk_index", i);
            doc.addField("text", chunks.get(i));
            doc.addField("embedding", toFloatList(embeddings.get(i)));
            docs.add(doc);
        }

        documentStore.save(docId, originalFilename, file.getContentType(), content);
        try {
            documentStore.saveExtractedText(docId, rawText);
        } catch (IOException e) {
            documentStore.delete(docId);
            throw e;
        }

        try {
            solrClient.add(docs);
            solrClient.commit();
            log.info("Solr 索引完成：{} 個 chunks，文件 '{}'", docs.size(), filename);
        } catch (Exception e) {
            documentStore.delete(docId);
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("Solr 寫入失敗: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("filename", filename);
        result.put("totalChunks", chunks.size());
        result.put("totalChars", text.length());
        result.put("rawChars", rawText.length());
        result.put("downloadable", true);
        return result;
    }

    /** 刪除指定 doc_id 的所有區塊。 */
    public int deleteByDocId(String docId) {
        try {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", "doc_id:\"" + docId + "\"");
            QueryResponse response = solrClient.query(params);
            long found = response.getResults().getNumFound();
            solrClient.deleteByQuery("doc_id:\"" + docId + "\"");
            solrClient.commit();
            documentStore.delete(docId);
            log.info("Solr 刪除 doc_id={}：{} 筆", docId, found);
            return (int) found;
        } catch (Exception e) {
            log.error("Solr 刪除失敗 doc_id={}: {}", docId, e.getMessage());
            return 0;
        }
    }

    /** 列出所有已擷取的文件（去重 doc_id）。 */
    public List<Map<String, Object>> listDocuments() {
        try {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", "*:*");
            params.set("rows", 0);
            params.set("facet", "true");
            params.set("facet.field", "doc_id");
            params.set("facet.field", "filename");
            params.set("facet.mincount", "1");

            QueryResponse response = solrClient.query(params);
            SolrDocumentList results = response.getResults();

            Map<String, Map<String, Object>> docMap = new LinkedHashMap<>();

            SolrDocumentList allDocs = getAllDocs();
            for (SolrDocument doc : allDocs) {
                String did = (String) doc.getFieldValue("doc_id");
                docMap.computeIfAbsent(did, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("doc_id", did);
                    m.put("filename", doc.getFieldValue("filename"));
                    m.put("chunks", 0);
                    m.put("created_at", doc.getFieldValue("created_at"));
                    return m;
                });
                Map<String, Object> m = docMap.get(did);
                m.put("chunks", ((Number) m.get("chunks")).intValue() + 1);
            }

            for (Map<String, Object> entry : docMap.values()) {
                entry.put("downloadable", documentStore.exists((String) entry.get("doc_id")));
            }
            return new ArrayList<>(docMap.values());
        } catch (Exception e) {
            log.error("Solr 列出文件失敗: {}", e.getMessage());
            return List.of();
        }
    }

    private SolrDocumentList getAllDocs() throws SolrServerException, IOException {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", "*:*");
        params.set("rows", "10000");
        params.set("fl", "doc_id,filename,created_at");
        QueryResponse response = solrClient.query(params);
        return response.getResults();
    }

    /** 檔名消毒：替換非 ASCII 字元與括號，避免 multipart header 編碼問題。 */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[^\\x20-\\x7E]", "_").replaceAll("[()（）]", "_");
    }

    /** 將 float[] 轉為 Solr DenseVectorField 所需的 List<Float>。 */
    private static List<Float> toFloatList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float v : values) {
            list.add(v);
        }
        return list;
    }

    /** 從 MultipartFile 擷取純文字。 */
    private String extractText(byte[] content, String name) throws IOException {
        if (name == null) return null;
        String lower = name.toLowerCase();

        try (InputStream is = new ByteArrayInputStream(content)) {
            if (lower.endsWith(".pdf")) {
                return extractPdf(is);
            } else if (lower.endsWith(".docx")) {
                return extractDocx(is);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("不支援的檔案格式，僅支援 PDF、DOCX、TXT、MD");
    }

    private String extractPdf(InputStream is) throws IOException {
        byte[] bytes = is.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private String extractDocx(InputStream is) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder text = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String paragraphText = paragraph.getText();
                    if (paragraphText != null && !paragraphText.isBlank()) {
                        text.append(paragraphText).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    appendTableText(text, table);
                }
            }
            return text.toString();
        }
    }

    private static void appendTableText(StringBuilder text, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText();
                cells.add(cellText == null ? "" : cellText.replace('\n', ' ').strip());
            }
            text.append(String.join("\t", cells)).append('\n');
        }
        text.append('\n');
    }
}

