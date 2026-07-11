package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 客户端缓存数据库
 * <p>
 * 使用 SQLite 存储缓存索引和热度信息，支持：
 * - 区块位置索引
 * - 访问次数统计
 * - 最后访问游戏时间
 * - 文件位置信息
 * <p>
 * 写入策略：单线程异步队列，所有写操作提交后立即返回，不阻塞调用者。
 * 热度数据为非核心数据，偶尔丢失不影响功能。
 * <p>
 * 数据库文件位于 config 目录，全局共享，支持多服务器多维度。
 */
public class ClientCacheDatabase implements Closeable {

    private static final String DB_FILE_NAME = "hassium_cache.db";

    private final Path dbPath;
    private Connection connection;

    // 异步写入队列：单线程消费，保证 SQLite 写入串行化且不阻塞调用者
    private final LinkedBlockingQueue<Runnable> writeQueue = new LinkedBlockingQueue<>();
    private final Thread writerThread;
    private volatile boolean closed = false;

    /**
     * 构造函数
     *
     * @param configDir 配置目录（数据库文件存放位置）
     */
    public ClientCacheDatabase(Path configDir) throws SQLException {
        this.dbPath = configDir.resolve(DB_FILE_NAME);
        initialize();

        writerThread = new Thread(this::drainLoop, "Hassium-DB-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * 初始化数据库
     */
    private void initialize() throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("Failed to create cache directory", e);
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        optimizeConnection();
        createTables();
        prepareStatements();

        Constants.LOG.info("Hassium: Client cache database initialized at {}", dbPath);
    }

    /**
     * 优化数据库连接
     */
    private void optimizeConnection() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA synchronous = OFF");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA cache_size = -8000");
            stmt.execute("PRAGMA temp_store = MEMORY");
            stmt.execute("PRAGMA mmap_size = 268435456");
            stmt.execute("PRAGMA page_size = 4096");
            stmt.execute("PRAGMA locking_mode = EXCLUSIVE");
            stmt.execute("PRAGMA wal_autocheckpoint = 1000");
            Constants.LOG.debug("Hassium: SQLite performance optimizations applied");
        }

        connection.setAutoCommit(false);
    }

    // 预编译的 SQL 语句（写操作专用，仅在 writerThread 中访问）
    private PreparedStatement upsertStmt;
    private PreparedStatement updateAccessStmt;
    private PreparedStatement deleteEntryStmt;
    private PreparedStatement updateSectionHashesStmt;

    private void prepareStatements() throws SQLException {
        String upsertSql = """
            INSERT INTO cache_entries
                (server_id, chunk_x, chunk_z, dimension, region_x, region_z, timestamp,
                 access_count, last_access_game_time, file_path, file_size, section_hashes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(server_id, chunk_x, chunk_z, dimension) DO UPDATE SET
                timestamp = excluded.timestamp,
                access_count = excluded.access_count,
                last_access_game_time = excluded.last_access_game_time,
                file_path = excluded.file_path,
                file_size = excluded.file_size,
                section_hashes = COALESCE(excluded.section_hashes, cache_entries.section_hashes)
            """;

        String updateAccessSql = """
            UPDATE cache_entries
            SET access_count = access_count + 1,
                last_access_game_time = ?
            WHERE server_id = ? AND chunk_x = ? AND chunk_z = ? AND dimension = ?
            """;

        String deleteEntrySql = """
            DELETE FROM cache_entries WHERE server_id = ? AND chunk_x = ? AND chunk_z = ? AND dimension = ?
            """;

        String updateSectionHashesSql = """
            UPDATE cache_entries
            SET section_hashes = ?
            WHERE server_id = ? AND chunk_x = ? AND chunk_z = ? AND dimension = ?
            """;

        upsertStmt = connection.prepareStatement(upsertSql);
        updateAccessStmt = connection.prepareStatement(updateAccessSql);
        deleteEntryStmt = connection.prepareStatement(deleteEntrySql);
        updateSectionHashesStmt = connection.prepareStatement(updateSectionHashesSql);

        Constants.LOG.debug("Hassium: Prepared statements initialized");
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cache_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    server_id TEXT NOT NULL DEFAULT 'unknown',
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    dimension TEXT NOT NULL DEFAULT 'minecraft:overworld',
                    region_x INTEGER NOT NULL,
                    region_z INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    access_count INTEGER NOT NULL DEFAULT 1,
                    last_access_game_time INTEGER NOT NULL DEFAULT 0,
                    file_path TEXT NOT NULL,
                    file_size INTEGER NOT NULL DEFAULT 0,
                    section_hashes BLOB DEFAULT NULL,
                    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                    UNIQUE(server_id, chunk_x, chunk_z, dimension)
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_server_chunk_pos
                ON cache_entries(server_id, chunk_x, chunk_z, dimension)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_server_hot_score
                ON cache_entries(server_id, access_count, last_access_game_time)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_server_region
                ON cache_entries(server_id, region_x, region_z, dimension)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_server_id
                ON cache_entries(server_id)
                """);

            // 迁移：为旧数据库添加 section_hashes 列
            try {
                stmt.execute("ALTER TABLE cache_entries ADD COLUMN section_hashes BLOB DEFAULT NULL");
            } catch (SQLException e) {
                // 列已存在，忽略
            }

            connection.commit();
        }
    }

    // ===== 异步写入 =====

    /**
     * 提交写入任务到异步队列，立即返回
     */
    private void submitWrite(Runnable task) {
        if (closed) {
            return;
        }
        writeQueue.offer(() -> {
            synchronized (this) {
                task.run();
            }
        });
    }

    /**
     * 单线程消费循环：逐个执行写入任务，串行访问 SQLite
     */
    private void drainLoop() {
        while (!closed || !writeQueue.isEmpty()) {
            try {
                Runnable task = writeQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: DB write task failed", e);
            }
        }
        // 退出前提交剩余任务
        Runnable remaining;
        while ((remaining = writeQueue.poll()) != null) {
            try {
                remaining.run();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: DB write task failed during shutdown", e);
            }
        }
    }

    // ===== 写入方法（异步，立即返回） =====

    /**
     * 插入或更新缓存条目（异步）
     */
    public void upsertEntry(CacheEntryInfo entry) {
        submitWrite(() -> {
            try {
                upsertStmt.setString(1, entry.serverId());
                upsertStmt.setInt(2, entry.chunkX());
                upsertStmt.setInt(3, entry.chunkZ());
                upsertStmt.setString(4, entry.dimension());
                upsertStmt.setInt(5, entry.regionX());
                upsertStmt.setInt(6, entry.regionZ());
                upsertStmt.setLong(7, entry.timestamp());
                upsertStmt.setInt(8, entry.accessCount());
                upsertStmt.setLong(9, entry.lastAccessGameTime());
                upsertStmt.setString(10, entry.filePath());
                upsertStmt.setLong(11, entry.fileSize());
                if (entry.sectionHashes() != null) {
                    upsertStmt.setBytes(12, entry.sectionHashes());
                } else {
                    upsertStmt.setNull(12, java.sql.Types.BLOB);
                }
                upsertStmt.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: upsertEntry failed for [{}, {}]", entry.chunkX(), entry.chunkZ(), e);
            }
        });
    }

    /**
     * 更新 section 哈希（异步）
     */
    public void updateSectionHashes(String serverId, int chunkX, int chunkZ, String dimension, byte[] sectionHashes) {
        submitWrite(() -> {
            try {
                if (sectionHashes != null) {
                    updateSectionHashesStmt.setBytes(1, sectionHashes);
                } else {
                    updateSectionHashesStmt.setNull(1, java.sql.Types.BLOB);
                }
                updateSectionHashesStmt.setString(2, serverId);
                updateSectionHashesStmt.setInt(3, chunkX);
                updateSectionHashesStmt.setInt(4, chunkZ);
                updateSectionHashesStmt.setString(5, dimension);
                updateSectionHashesStmt.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: updateSectionHashes failed for [{}, {}]", chunkX, chunkZ, e);
            }
        });
    }

    /**
     * 读取 section 哈希（同步）
     *
     * @return section 哈希字节数组，不存在返回 null
     */
    public synchronized byte[] readSectionHashes(String serverId, int chunkX, int chunkZ, String dimension) throws SQLException {
        String sql = "SELECT section_hashes FROM cache_entries WHERE server_id = ? AND chunk_x = ? AND chunk_z = ? AND dimension = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, serverId);
            pstmt.setInt(2, chunkX);
            pstmt.setInt(3, chunkZ);
            pstmt.setString(4, dimension);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes("section_hashes");
                }
                return null;
            }
        }
    }

    /**
     * 更新访问信息（异步）
     */
    public void updateAccessInfo(String serverId, int chunkX, int chunkZ, String dimension, long currentGameTime) {
        submitWrite(() -> {
            try {
                updateAccessStmt.setLong(1, currentGameTime);
                updateAccessStmt.setString(2, serverId);
                updateAccessStmt.setInt(3, chunkX);
                updateAccessStmt.setInt(4, chunkZ);
                updateAccessStmt.setString(5, dimension);
                updateAccessStmt.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: updateAccessInfo failed for [{}, {}]", chunkX, chunkZ, e);
            }
        });
    }

    /**
     * 删除缓存条目（异步）
     */
    public void deleteEntry(String serverId, int chunkX, int chunkZ, String dimension) {
        submitWrite(() -> {
            try {
                deleteEntryStmt.setString(1, serverId);
                deleteEntryStmt.setInt(2, chunkX);
                deleteEntryStmt.setInt(3, chunkZ);
                deleteEntryStmt.setString(4, dimension);
                deleteEntryStmt.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: deleteEntry failed for [{}, {}]", chunkX, chunkZ, e);
            }
        });
    }

    /**
     * 删除指定服务器的所有条目（异步）
     */
    public void deleteByServer(String serverId) {
        submitWrite(() -> {
            try {
                String sql = "DELETE FROM cache_entries WHERE server_id = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setString(1, serverId);
                    pstmt.executeUpdate();
                    connection.commit();
                }
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: deleteByServer failed", e);
            }
        });
    }

    /**
     * 删除指定服务器和维度的所有条目（异步）
     */
    public void deleteByServerAndDimension(String serverId, String dimension) {
        submitWrite(() -> {
            try {
                String sql = "DELETE FROM cache_entries WHERE server_id = ? AND dimension = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setString(1, serverId);
                    pstmt.setString(2, dimension);
                    pstmt.executeUpdate();
                    connection.commit();
                }
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: deleteByServerAndDimension failed", e);
            }
        });
    }

    /**
     * 删除指定服务器和 region 的所有条目（异步）
     */
    public void deleteByServerAndRegion(String serverId, int regionX, int regionZ, String dimension) {
        submitWrite(() -> {
            try {
                String sql = "DELETE FROM cache_entries WHERE server_id = ? AND region_x = ? AND region_z = ? AND dimension = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setString(1, serverId);
                    pstmt.setInt(2, regionX);
                    pstmt.setInt(3, regionZ);
                    pstmt.setString(4, dimension);
                    pstmt.executeUpdate();
                    connection.commit();
                }
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: deleteByServerAndRegion failed", e);
            }
        });
    }

    /**
     * 清空指定服务器的所有数据（异步）
     */
    public void clearByServer(String serverId) {
        submitWrite(() -> {
            try {
                String sql = "DELETE FROM cache_entries WHERE server_id = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setString(1, serverId);
                    pstmt.executeUpdate();
                    connection.commit();
                }
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: clearByServer failed", e);
            }
        });
    }

    /**
     * 清空所有数据（异步）
     */
    public void clearAll() {
        submitWrite(() -> {
            try {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("DELETE FROM cache_entries");
                    connection.commit();
                }
            } catch (SQLException e) {
                Constants.LOG.debug("Hassium: clearAll failed", e);
            }
        });
    }

    // ===== 读取方法（同步，直接在调用者线程执行） =====

    /**
     * 查询缓存条目（使用局部 PreparedStatement，线程安全）
     */
    public synchronized CacheEntryInfo getEntry(String serverId, int chunkX, int chunkZ, String dimension) throws SQLException {
        String sql = "SELECT * FROM cache_entries WHERE server_id = ? AND chunk_x = ? AND chunk_z = ? AND dimension = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, serverId);
            pstmt.setInt(2, chunkX);
            pstmt.setInt(3, chunkZ);
            pstmt.setString(4, dimension);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return resultSetToEntry(rs);
                }
                return null;
            }
        }
    }

    /**
     * 获取指定服务器的所有缓存条目（用于清理扫描）
     */
    public synchronized List<CacheEntryInfo> getAllEntriesByServer(String serverId) throws SQLException {
        String sql = "SELECT * FROM cache_entries WHERE server_id = ? ORDER BY id";
        List<CacheEntryInfo> entries = new ArrayList<>();

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, serverId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(resultSetToEntry(rs));
                }
            }
        }
        return entries;
    }

    /**
     * 获取指定服务器的冷区块列表（按热度排序，冷区块在前）
     */
    public synchronized List<CacheEntryInfo> getColdEntriesByServer(String serverId, long currentGameTime, int limit) throws SQLException {
        String sql = """
            SELECT *,
                (0.7 * (1.0 / (1.0 + MAX(0, ? - last_access_game_time))) +
                 0.3 * (1.0 / (1.0 + access_count))) AS hot_score
            FROM cache_entries
            WHERE server_id = ?
            ORDER BY hot_score ASC
            LIMIT ?
            """;

        List<CacheEntryInfo> entries = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, currentGameTime);
            pstmt.setString(2, serverId);
            pstmt.setInt(3, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CacheEntryInfo entry = resultSetToEntry(rs);
                    double hotScore = rs.getDouble("hot_score");
                    entries.add(entry.withHotScore(hotScore));
                }
            }
        }
        return entries;
    }

    /**
     * 获取指定服务器的缓存统计信息
     */
    public synchronized CacheStats getStatsByServer(String serverId) throws SQLException {
        String sql = """
            SELECT
                COUNT(*) as entry_count,
                COALESCE(SUM(file_size), 0) as total_size
            FROM cache_entries
            WHERE server_id = ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, serverId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new CacheStats(
                            rs.getInt("entry_count"),
                            rs.getLong("total_size")
                    );
                }
                return new CacheStats(0, 0);
            }
        }
    }

    /**
     * 获取指定服务器和维度的缓存统计
     */
    public synchronized CacheStats getStatsByServerAndDimension(String serverId, String dimension) throws SQLException {
        String sql = """
            SELECT
                COUNT(*) as entry_count,
                COALESCE(SUM(file_size), 0) as total_size
            FROM cache_entries
            WHERE server_id = ? AND dimension = ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, serverId);
            pstmt.setString(2, dimension);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new CacheStats(
                            rs.getInt("entry_count"),
                            rs.getLong("total_size")
                    );
                }
                return new CacheStats(0, 0);
            }
        }
    }

    /**
     * 检查条目是否存在
     */
    public synchronized boolean exists(String serverId, int chunkX, int chunkZ, String dimension) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM cache_entries
            WHERE server_id = ? AND chunk_x = ? AND chunk_z = ? AND dimension = ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, serverId);
            pstmt.setInt(2, chunkX);
            pstmt.setInt(3, chunkZ);
            pstmt.setString(4, dimension);

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 获取指定服务器的条目总数
     */
    public synchronized int getEntryCountByServer(String serverId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM cache_entries WHERE server_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, serverId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * 获取条目总数
     */
    public synchronized int getEntryCount() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM cache_entries")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        // 等待写入队列排空（最多 2 秒）
        try {
            writerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (connection != null) {
            try {
                if (upsertStmt != null) { upsertStmt.close(); }
                if (updateAccessStmt != null) { updateAccessStmt.close(); }
                if (deleteEntryStmt != null) { deleteEntryStmt.close(); }
                if (updateSectionHashesStmt != null) { updateSectionHashesStmt.close(); }

                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }

                connection.close();
            } catch (SQLException e) {
                throw new IOException("Failed to close database connection", e);
            }
        }
    }

    private CacheEntryInfo resultSetToEntry(ResultSet rs) throws SQLException {
        return new CacheEntryInfo(
                rs.getString("server_id"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getString("dimension"),
                rs.getInt("region_x"),
                rs.getInt("region_z"),
                rs.getLong("timestamp"),
                rs.getInt("access_count"),
                rs.getLong("last_access_game_time"),
                rs.getString("file_path"),
                rs.getLong("file_size"),
                0.0,
                rs.getBytes("section_hashes")
        );
    }

    /**
     * 缓存条目信息
     */
    public record CacheEntryInfo(
            String serverId,
            int chunkX,
            int chunkZ,
            String dimension,
            int regionX,
            int regionZ,
            long timestamp,
            int accessCount,
            long lastAccessGameTime,
            String filePath,
            long fileSize,
            double hotScore,
            byte[] sectionHashes
    ) {
        public CacheEntryInfo withHotScore(double hotScore) {
            return new CacheEntryInfo(
                    serverId, chunkX, chunkZ, dimension, regionX, regionZ,
                    timestamp, accessCount, lastAccessGameTime,
                    filePath, fileSize, hotScore, sectionHashes
            );
        }
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(
            int entryCount,
            long totalSize
    ) {
    }
}
