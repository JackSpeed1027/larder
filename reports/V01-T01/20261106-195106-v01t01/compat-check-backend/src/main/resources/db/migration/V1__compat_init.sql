-- 验证用迁移：确认 Flyway 能在该版本组合下发现并执行迁移。
CREATE TABLE compat_probe (
    id BIGINT PRIMARY KEY,
    note VARCHAR(64) NOT NULL
);

INSERT INTO compat_probe (id, note) VALUES (1, 'flyway+mapper ok');
