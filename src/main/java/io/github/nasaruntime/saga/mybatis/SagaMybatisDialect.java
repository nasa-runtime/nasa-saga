package io.github.nasaruntime.saga.mybatis;

import io.github.nasaruntime.saga.SagaPersistenceException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Java participant 本地表使用的数据库方言。
 */
public enum SagaMybatisDialect {

    MYSQL,
    POSTGRESQL;

    /**
     * 业务作用：从当前事务连接识别时间裁决方言，避免宿主错误配置弱化 lease 到期门禁。
     *
     * @param connection 当前数据库连接
     * @return MySQL 或 PostgreSQL 方言；其它数据库及元数据读取失败时拒绝操作。
     */
    public static SagaMybatisDialect from(Connection connection) {
        try {
            return switch (connection.getMetaData().getDatabaseProductName()) {
                case "MySQL" -> MYSQL;
                case "PostgreSQL" -> POSTGRESQL;
                default -> throw new IllegalArgumentException("unsupported Saga database");
            };
        } catch (SQLException exception) {
            throw new SagaPersistenceException("database dialect unavailable", exception);
        }
    }
}
