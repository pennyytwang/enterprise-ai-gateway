package com.charphone.aigateway.rag;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charphone.aigateway.config.AppProperties;

/**
 * RAG 原始檔儲存：保存上傳檔案本體與顯示用檔名，供知識庫來源下載。
 *
 * <p>Solr 只保存切塊文字，原始檔另外保存在 {@code gateway.rag-storage-dir}。
 * 舊文件若沒有原始檔，{@link #find(String)} 會回傳空值，下載 API 回應 404。</p>
 */
@Component
public class RagDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentStore.class);
    private static final Pattern SAFE_DOC_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String ORIGINAL_FILE = "original";
    private static final String EXTRACTED_TEXT_FILE = "extracted.txt";
    private static final String METADATA_FILE = "metadata.properties";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final Path storageDir;

    public RagDocumentStore(AppProperties props) {
        this.storageDir = Path.of(props.getRagStorageDir()).toAbsolutePath().normalize();
    }

    /**
     * 保存原始檔與中繼資料。
     *
     * @param docId            文件識別碼（UUID）
     * @param originalFilename 使用者原始檔名
     * @param contentType      瀏覽器提供的 Content-Type，可為 null
     * @param content          檔案內容
     * @return 可下載的原始檔資訊
     * @throws IOException 寫入失敗
     */
    public StoredDocument save(String docId, String originalFilename, String contentType, byte[] content)
            throws IOException {
        Path docDir = resolveDocDir(docId);
        Files.createDirectories(docDir);

        Path original = docDir.resolve(ORIGINAL_FILE);
        Files.write(original, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        String filename = normalizeFilename(originalFilename);
        String resolvedContentType = resolveContentType(contentType, filename);
        Properties metadata = new Properties();
        metadata.setProperty("filename", filename);
        metadata.setProperty("contentType", resolvedContentType);
        metadata.setProperty("size", String.valueOf(content.length));
        metadata.setProperty("createdAt", Instant.now().toString());
        try (OutputStream out = Files.newOutputStream(docDir.resolve(METADATA_FILE))) {
            metadata.store(out, "RAG original document");
        }

        log.info("已保存 RAG 原始檔 docId={} filename={} bytes={}", docId, filename, content.length);
        return new StoredDocument(original, filename, resolvedContentType, content.length);
    }

    /**
     * 保存未清理的擷取文字，供日後除錯、重新切塊或追溯來源。
     *
     * @param docId 文件識別碼
     * @param text  原始擷取文字
     * @throws IOException 寫入失敗
     */
    public void saveExtractedText(String docId, String text) throws IOException {
        Path docDir = resolveDocDir(docId);
        Files.createDirectories(docDir);
        Files.writeString(docDir.resolve(EXTRACTED_TEXT_FILE), text == null ? "" : text);
        log.info("已保存 RAG 原始擷取文字 docId={} chars={}", docId, text == null ? 0 : text.length());
    }

    /**
     * 查詢可下載的原始檔。
     *
     * @param docId 文件識別碼
     * @return 原始檔資訊；不存在或 docId 不合法時為空
     */
    public Optional<StoredDocument> find(String docId) {
        if (!isValidDocId(docId)) {
            return Optional.empty();
        }
        Path docDir = resolveDocDir(docId);
        Path original = docDir.resolve(ORIGINAL_FILE);
        if (!Files.isRegularFile(original)) {
            return Optional.empty();
        }

        Properties metadata = loadMetadata(docDir);
        String filename = metadata.getProperty("filename", "document-" + docId);
        String contentType = metadata.getProperty("contentType", DEFAULT_CONTENT_TYPE);
        try {
            return Optional.of(new StoredDocument(original, filename, contentType, Files.size(original)));
        } catch (IOException e) {
            log.warn("讀取 RAG 原始檔大小失敗 docId={}: {}", docId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 檢查文件是否有可下載的原始檔。
     *
     * @param docId 文件識別碼
     * @return true 表示可下載
     */
    public boolean exists(String docId) {
        return find(docId).isPresent();
    }

    /**
     * 刪除指定文件的原始檔，失敗只記錄不拋出。
     *
     * @param docId 文件識別碼
     */
    public void delete(String docId) {
        if (!isValidDocId(docId)) {
            return;
        }
        Path docDir = resolveDocDir(docId);
        if (!Files.exists(docDir)) {
            return;
        }
        try (var paths = Files.walk(docDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("刪除 RAG 原始檔失敗 path={}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("刪除 RAG 原始檔目錄失敗 docId={}: {}", docId, e.getMessage());
        }
    }

    private Properties loadMetadata(Path docDir) {
        Properties metadata = new Properties();
        Path metadataPath = docDir.resolve(METADATA_FILE);
        if (Files.isRegularFile(metadataPath)) {
            try (var in = Files.newInputStream(metadataPath)) {
                metadata.load(in);
            } catch (IOException e) {
                log.warn("讀取 RAG 原始檔中繼資料失敗 path={}: {}", metadataPath, e.getMessage());
            }
        }
        return metadata;
    }

    private Path resolveDocDir(String docId) {
        if (!isValidDocId(docId)) {
            throw new IllegalArgumentException("不合法的文件識別碼");
        }
        Path docDir = storageDir.resolve(docId).normalize();
        if (!docDir.getParent().equals(storageDir)) {
            throw new IllegalArgumentException("不合法的文件路徑");
        }
        return docDir;
    }

    private boolean isValidDocId(String docId) {
        return docId != null && SAFE_DOC_ID.matcher(docId).matches();
    }

    public static String normalizeFilename(String name) {
        String filename = name == null ? "" : name;
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (slash >= 0) {
            filename = filename.substring(slash + 1);
        }
        filename = filename.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "_").trim();
        if (filename.isEmpty()) {
            return "document";
        }
        return filename.length() > 180 ? filename.substring(0, 180) : filename;
    }

    private String resolveContentType(String contentType, String filename) {
        if (contentType != null && !contentType.isBlank()
                && !DEFAULT_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            return contentType;
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown; charset=UTF-8";
        }
        return DEFAULT_CONTENT_TYPE;
    }

    /**
     * 可下載的原始檔資訊。
     *
     * @param path        檔案路徑
     * @param filename    下載檔名
     * @param contentType MIME 類型
     * @param size        檔案大小
     */
    public record StoredDocument(Path path, String filename, String contentType, long size) {
    }
}
