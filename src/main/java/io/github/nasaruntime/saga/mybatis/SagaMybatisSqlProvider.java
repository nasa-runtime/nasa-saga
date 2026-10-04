package io.github.nasaruntime.saga.mybatis;

import java.util.Map;

/**
 * MyBatis 方言 SQL 提供器。
 */
public final class SagaMybatisSqlProvider {

    /**
     * 业务作用：限制 SQL provider 为静态入口，避免产生不承载事务身份的实例。
     * 参数说明：无。
     */
    private SagaMybatisSqlProvider() {}

    /**
     * 业务作用：将 start 裁决限定在当前 token 且未到期的 lease 内，过期但尚未重领也不得回写。
     *
     * @param parameters MyBatis 命名参数集合
     * @return 带数据库当前时刻门禁的条件更新 SQL
     */
    public static String settleStartIntent(Map<String, Object> parameters) {
        return "UPDATE saga_start_intent SET state = #{state}, last_http_status = #{statusCode}, "
                + "last_error_code = #{errorCode}, next_attempt_at_ms = #{nextAttemptAtMs}, "
                + "lease_owner = NULL, lease_until_ms = NULL, updated_at_ms = #{nowMs} "
                + "WHERE intent_id = #{intentId} AND state = 'IN_FLIGHT' "
                + "AND lease_owner = #{owner} AND fencing_token = #{fencingToken} "
                + "AND lease_until_ms > " + currentMillis(parameters);
    }

    /**
     * 业务作用：以数据库时钟和 token 同时约束 result 完成，防止迟到收据越过失权边界。
     *
     * @param parameters MyBatis 命名参数集合
     * @return 带 lease 到期条件的 result 更新 SQL
     */
    public static String settleResultOutbox(Map<String, Object> parameters) {
        return "UPDATE saga_result_outbox SET state = #{state}, next_attempt_at_ms = #{nextAttemptAtMs}, "
                + "last_error_code = #{errorCode}, "
                + "lease_owner = NULL, lease_until_ms = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = #{eventId} AND state = 'IN_FLIGHT' "
                + "AND lease_owner = #{owner} AND fencing_token = #{fencingToken} "
                + "AND lease_until_ms > " + currentMillis(parameters);
    }

    /**
     * 业务作用：提供实际执行时刻而非客户端传入旧时刻，行锁等待不能延长 lease。
     *
     * @param parameters 包含当前连接方言的参数集合
     * @return 数据库 epoch 毫秒表达式；方言缺失时拒绝生成 SQL。
     */
    private static String currentMillis(Map<String, Object> parameters) {
        return switch ((SagaMybatisDialect) parameters.get("dialect")) {
            case MYSQL -> "(UNIX_TIMESTAMP(SYSDATE(3)) * 1000)";
            case POSTGRESQL -> "(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)";
        };
    }

    /**
     * 业务作用：生成与 Rust replay claim 相同的数据库唯一占用语句。
     *
     * @param parameters MyBatis 命名参数集合
     * @return 当前数据库方言的原子 claim SQL
     */
    public static String claimReplay(Map<String, Object> parameters) {
        SagaMybatisDialect dialect = (SagaMybatisDialect) parameters.get("dialect");
        if (dialect == null) {
            throw new IllegalArgumentException("Saga MyBatis dialect is required");
        }
        return switch (dialect) {
            case MYSQL -> "INSERT IGNORE INTO nasa_saga_http_replay_claim "
                    + "(producer, nonce, expires_at_ms) VALUES (#{producer}, #{nonce}, #{expiresAtMs})";
            case POSTGRESQL -> "INSERT INTO nasa_saga_http_replay_claim "
                    + "(producer, nonce, expires_at_ms) VALUES (#{producer}, #{nonce}, #{expiresAtMs}) "
                    + "ON CONFLICT (producer, nonce) DO NOTHING";
        };
    }

    /**
     * 业务作用：按数据库方言生成有界 replay claim 回收语句，避免无限增长破坏认证数据面。
     *
     * @param parameters MyBatis 命名参数集合
     * @return 当前数据库方言的回收 SQL
     */
    public static String purgeReplay(Map<String, Object> parameters) {
        SagaMybatisDialect dialect = (SagaMybatisDialect) parameters.get("dialect");
        if (dialect == null) {
            throw new IllegalArgumentException("Saga MyBatis dialect is required");
        }
        return switch (dialect) {
            case MYSQL -> "DELETE FROM nasa_saga_http_replay_claim WHERE expires_at_ms < #{nowMs} "
                    + "ORDER BY expires_at_ms LIMIT #{limit}";
            case POSTGRESQL -> "DELETE FROM nasa_saga_http_replay_claim WHERE ctid IN "
                    + "(SELECT ctid FROM nasa_saga_http_replay_claim WHERE expires_at_ms < #{nowMs} "
                    + "ORDER BY expires_at_ms LIMIT #{limit})";
        };
    }

    /**
     * 业务作用：只允许四个固定 gate 状态列参与条件更新，防止动态 SQL 标识符成为注入入口。
     *
     * @param parameters MyBatis 命名参数集合
     * @return 经白名单约束的 gate 更新 SQL
     */
    public static String updateGateStatus(Map<String, Object> parameters) {
        String column = (String) parameters.get("columnName");
        String safeColumn = switch (column) {
            case "forward_status", "cancel_status", "compensation_status", "resolution_status" -> column;
            default -> throw new IllegalArgumentException("unsupported participant gate status column");
        };
        return "UPDATE saga_participant_step SET " + safeColumn + " = #{nextStatus}, updated_at = CURRENT_TIMESTAMP "
                + "WHERE saga_id = #{sagaId} AND step_name = #{stepName} AND "
                + safeColumn + " = #{expectedStatus}";
    }

    /**
     * 业务作用：仅为固定阶段生成完整裁决更新，禁止外部字符串成为 SQL 列名。
     *
     * @param parameters MyBatis 命名参数
     * @return 同一 gate 行的三字段更新语句；非法阶段拒绝。
     */
    public static String updateGateResult(Map<String, Object> parameters) {
        String phase = (String) parameters.get("phase");
        String prefix = switch (phase) {
            case "execute", "cancel", "compensate", "resolve" -> phase + "_result_";
            default -> throw new IllegalArgumentException("unsupported participant result phase");
        };
        return "UPDATE saga_participant_step SET " + prefix + "status = #{result.status}, "
                + prefix + "terminal_status = #{result.terminalStatus}, " + prefix + "reason_code = #{result.reasonCode}, "
                + "updated_at = CURRENT_TIMESTAMP WHERE saga_id = #{sagaId} AND step_name = #{stepName}";
    }

}
