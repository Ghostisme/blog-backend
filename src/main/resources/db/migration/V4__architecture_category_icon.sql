-- The architecture topic uses the frontend ApartmentOutlined icon. Keep this
-- idempotent so a fresh database and the existing cloud database converge.
INSERT INTO category (code, name_zh, name_en, icon, sort_order)
VALUES ('architecture', '架构', 'Architecture', 'apartment', 60)
ON DUPLICATE KEY UPDATE
    name_zh = '架构',
    name_en = 'Architecture',
    icon = 'apartment',
    sort_order = 60;
