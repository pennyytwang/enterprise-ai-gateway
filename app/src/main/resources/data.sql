-- =====================================================================
-- AI Gateway 示範種子資料
-- =====================================================================
-- 示範 API Key（明文）：demo-api-key-charphone
-- 僅儲存 SHA-256 雜湊；此金鑰只供本機展示，請勿用於正式環境。
-- =====================================================================

INSERT INTO api_client (client_name, api_key_hash, role)
SELECT 'demo-client', 'a5b564c4108a2d8e29aa3c6c1961de7c9f6a6e88a7173c243d19f8aa86772c11', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM api_client WHERE client_name = 'demo-client');
