-- 仅在停写、备份并确认两表缺少目标列后执行；可空列不构成首次输入证据。
-- 库存存量绑定必须来自可信原始命令，不能用当前预留行反推，证据不足时保持关闭。
ALTER TABLE saga_participant_step ADD COLUMN execute_input_digest CHAR(64) NULL;
ALTER TABLE saga_participant_inbox ADD COLUMN execute_input_digest CHAR(64) NULL;
