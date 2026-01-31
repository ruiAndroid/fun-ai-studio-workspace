package fun.ai.studio.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Workspace（用户在线开发容器）配置
 */
@Component
@ConfigurationProperties(prefix = "funai.workspace")
public class WorkspaceProperties {

    /**
     * 是否启用 workspace 功能
     */
    private boolean enabled = false;

    /**
     * workspace 宿主机持久化根目录：{hostRoot}/{userId}/apps/{appId}
     */
    private String hostRoot;

    /**
     * workspace 容器镜像（建议使用你自己的 ACR 镜像）
     */
    private String image;

    /**
     * 可选：registry 登录用户名（建议用 robot 账号）。
     * 若不配置，可通过环境变量 REGISTRY_USERNAME/REGISTRY_PASSWORD 注入。
     */
    private String registryUsername;

    /**
     * 可选：registry 登录密码/Token（建议用 robot token）。
     * 若不配置，可通过环境变量 REGISTRY_USERNAME/REGISTRY_PASSWORD 注入。
     */
    private String registryPassword;

    /**
     * 容器内 dev server 端口（Vite 默认 5173）
     */
    private int containerPort = 5173;

    /**
     * 预览地址的 baseUrl（建议配置为 nginx 的域名），用于后端生成 previewUrl
     * 示例：
     * - https://preview.example.com
     *
     * 推荐配合 nginx 反代（只开 80/443）：最终 previewUrl 形如
     * {previewBaseUrl}{previewPathPrefix}/{userId}/
     */
    private String previewBaseUrl;

    /**
     * nginx 反代预览的路径前缀（不以 / 结尾），默认 /ws
     * 最终会拼接为 {previewBaseUrl}{previewPathPrefix}/{userId}/
     */
    private String previewPathPrefix = "/ws";

    /**
     * nginx auth_request 调用内部端口查询接口的共享密钥（强烈建议配置）
     * nginx 子请求需要携带 Header：X-WS-Token
     */
    private String nginxAuthToken;

    /**
     * 宿主机端口分配起始值
     */
    private int hostPortBase = 20000;

    /**
     * 单机最多尝试分配的端口数量（从 hostPortBase 开始向后扫描）
     */
    private int hostPortScanSize = 2000;

    /**
     * 容器内 workspace 挂载目录
     */
    private String containerWorkdir = "/workspace";

    /**
     * 容器名前缀：ws-u-{userId}
     */
    private String containerNamePrefix = "ws-u-";

    /**
     * 容器网络名（podman/docker）：用于让 workspace 容器访问同机的其它服务容器（如 Verdaccio）。
     * 建议在宿主机创建网络：docker network create funai-net
     */
    private String networkName = "funai-net";

    /**
     * npm registry（推荐指向同机 Verdaccio）：http://verdaccio:4873
     * 若为空，则不做注入，脚本侧也不会强行覆盖。
     */
    private String npmRegistry = "http://verdaccio:4873";

    /**
     * npm cache 策略（控制容器磁盘膨胀）：
     * - APP：将 npm cache 放到应用目录内（{APP_DIR}/.npm-cache），删除项目目录即可回收（推荐）
     * - CONTAINER：使用 npm 默认缓存（通常是 ~/.npm），可能导致用户容器层长期膨胀
     * - DISABLED：将 cache 放到临时目录并在任务结束后清理（更省磁盘，但会降低重复安装速度；有 Verdaccio 时影响较小）
     */
    private String npmCacheMode = "APP";

    /**
     * npm cache 最大大小（MB），超过后会在受控任务中触发清理。
     * - 0 或负数：不限制
     * - 仅对 APP/DISABLED 模式生效（CONTAINER 模式不做自动清理，避免误删用户手工状态）
     */
    private long npmCacheMaxMb = 2048;

    private String httpProxy;
    private String httpsProxy;
    private String noProxy;

    /**
     * docker run 资源限制（可选）
     * <p>
     * 说明：
     * - 不配置则不注入任何限制（保持历史行为）
     * - 典型值：dockerMemory=1400m, dockerCpus=1.5, pidsLimit=512
     */
    private String dockerMemory;

    /**
     * docker run --memory-swap（可选）
     * - 例如：1400m、2g
     * - Docker 语义：memory+swap 的总量上限（不同运行时实现可能略有差异）
     */
    private String dockerMemorySwap;

    /**
     * docker run --cpus（可选）
     * 例如：1.0 / 1.5 / 2.0
     */
    private Double dockerCpus;

    /**
     * docker run --pids-limit（可选）
     * 防止 fork 炸弹或依赖安装时创建过多进程把宿主机拖死。
     */
    private Integer pidsLimit;

    /**
     * 无操作多少分钟后自动 stop run（默认 10 分钟）
     */
    private int idleStopRunMinutes = 10;

    /**
     * 无操作多少分钟后自动 stop 容器（默认 20 分钟）
     */
    private int idleStopContainerMinutes = 20;

    /**
     * run/status 中 STARTING 的超时秒数；超过后仍未拿到 pid，则认为启动失败（避免前端无限转圈）
     */
    private int runStartingTimeoutSeconds = 300;

    /**
     * 受控任务日志保留策略：同一 userId + type（BUILD/INSTALL/START/DEV）最多保留最近 N 份日志文件（忽略 appId）。
     * <p>
     * 日志文件名形如：run/run-{type}-{appId}-{timestamp}.log
     * - 设置为 0 或负数：不做清理（可能导致 run 目录日志无限增长）
     * <p>
     * 说明：用户可能频繁切换不同 appId，如果按 appId 维度保留，run 目录总体仍会持续增长。
     */
    private int runLogKeepPerType = 3;

    /**
     * Mongo（独立服务器模式）：连接到独立 MongoDB 服务器
     * 数据库命名：db_{appId}（保持原有命名方式，对前端无影响）
     */
    private MongoProperties mongo = new MongoProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHostRoot() {
        return hostRoot;
    }

    public void setHostRoot(String hostRoot) {
        this.hostRoot = hostRoot;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getRegistryUsername() {
        return registryUsername;
    }

    public void setRegistryUsername(String registryUsername) {
        this.registryUsername = registryUsername;
    }

    public String getRegistryPassword() {
        return registryPassword;
    }

    public void setRegistryPassword(String registryPassword) {
        this.registryPassword = registryPassword;
    }

    public int getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(int containerPort) {
        this.containerPort = containerPort;
    }

    public String getPreviewBaseUrl() {
        return previewBaseUrl;
    }

    public void setPreviewBaseUrl(String previewBaseUrl) {
        this.previewBaseUrl = previewBaseUrl;
    }

    public String getPreviewPathPrefix() {
        return previewPathPrefix;
    }

    public void setPreviewPathPrefix(String previewPathPrefix) {
        this.previewPathPrefix = previewPathPrefix;
    }

    public String getNginxAuthToken() {
        return nginxAuthToken;
    }

    public void setNginxAuthToken(String nginxAuthToken) {
        this.nginxAuthToken = nginxAuthToken;
    }

    public int getHostPortBase() {
        return hostPortBase;
    }

    public void setHostPortBase(int hostPortBase) {
        this.hostPortBase = hostPortBase;
    }

    public int getHostPortScanSize() {
        return hostPortScanSize;
    }

    public void setHostPortScanSize(int hostPortScanSize) {
        this.hostPortScanSize = hostPortScanSize;
    }

    public String getContainerWorkdir() {
        return containerWorkdir;
    }

    public void setContainerWorkdir(String containerWorkdir) {
        this.containerWorkdir = containerWorkdir;
    }

    public String getContainerNamePrefix() {
        return containerNamePrefix;
    }

    public void setContainerNamePrefix(String containerNamePrefix) {
        this.containerNamePrefix = containerNamePrefix;
    }

    public String getNetworkName() {
        return networkName;
    }

    public void setNetworkName(String networkName) {
        this.networkName = networkName;
    }

    public String getNpmRegistry() {
        return npmRegistry;
    }

    public void setNpmRegistry(String npmRegistry) {
        this.npmRegistry = npmRegistry;
    }

    public String getNpmCacheMode() {
        return npmCacheMode;
    }

    public void setNpmCacheMode(String npmCacheMode) {
        this.npmCacheMode = npmCacheMode;
    }

    public long getNpmCacheMaxMb() {
        return npmCacheMaxMb;
    }

    public void setNpmCacheMaxMb(long npmCacheMaxMb) {
        this.npmCacheMaxMb = npmCacheMaxMb;
    }

    public String getHttpProxy() {
        return httpProxy;
    }

    public void setHttpProxy(String httpProxy) {
        this.httpProxy = httpProxy;
    }

    public String getHttpsProxy() {
        return httpsProxy;
    }

    public void setHttpsProxy(String httpsProxy) {
        this.httpsProxy = httpsProxy;
    }

    public String getNoProxy() {
        return noProxy;
    }

    public void setNoProxy(String noProxy) {
        this.noProxy = noProxy;
    }

    public String getDockerMemory() {
        return dockerMemory;
    }

    public void setDockerMemory(String dockerMemory) {
        this.dockerMemory = dockerMemory;
    }

    public String getDockerMemorySwap() {
        return dockerMemorySwap;
    }

    public void setDockerMemorySwap(String dockerMemorySwap) {
        this.dockerMemorySwap = dockerMemorySwap;
    }

    public Double getDockerCpus() {
        return dockerCpus;
    }

    public void setDockerCpus(Double dockerCpus) {
        this.dockerCpus = dockerCpus;
    }

    public Integer getPidsLimit() {
        return pidsLimit;
    }

    public void setPidsLimit(Integer pidsLimit) {
        this.pidsLimit = pidsLimit;
    }

    public int getIdleStopRunMinutes() {
        return idleStopRunMinutes;
    }

    public void setIdleStopRunMinutes(int idleStopRunMinutes) {
        this.idleStopRunMinutes = idleStopRunMinutes;
    }

    public int getIdleStopContainerMinutes() {
        return idleStopContainerMinutes;
    }

    public void setIdleStopContainerMinutes(int idleStopContainerMinutes) {
        this.idleStopContainerMinutes = idleStopContainerMinutes;
    }

    public int getRunStartingTimeoutSeconds() {
        return runStartingTimeoutSeconds;
    }

    public void setRunStartingTimeoutSeconds(int runStartingTimeoutSeconds) {
        this.runStartingTimeoutSeconds = runStartingTimeoutSeconds;
    }

    public int getRunLogKeepPerType() {
        return runLogKeepPerType;
    }

    public void setRunLogKeepPerType(int runLogKeepPerType) {
        this.runLogKeepPerType = runLogKeepPerType;
    }

    public MongoProperties getMongo() {
        return mongo;
    }

    public void setMongo(MongoProperties mongo) {
        this.mongo = mongo;
    }

    public static class MongoProperties {
        /**
         * 是否启用 MongoDB 功能
         */
        private boolean enabled = false;

        /**
         * MongoDB 服务器地址（独立服务器）
         */
        private String host = "172.21.138.88";

        /**
         * MongoDB 服务器端口
         */
        private int port = 27017;

        /**
         * 认证用户名
         */
        private String username;

        /**
         * 认证密码
         */
        private String password;

        /**
         * 认证数据库
         */
        private String authSource = "admin";

        /**
         * 数据库名前缀：最终 dbName = {dbNamePrefix}{appId}
         * 例如：db_ -> db_2001（保持原有命名方式）
         */
        private String dbNamePrefix = "db_";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getAuthSource() {
            return authSource;
        }

        public void setAuthSource(String authSource) {
            this.authSource = authSource;
        }

        public String getDbNamePrefix() {
            return dbNamePrefix;
        }

        public void setDbNamePrefix(String dbNamePrefix) {
            this.dbNamePrefix = dbNamePrefix;
        }

        /**
         * 根据 appId 生成数据库名（保持原有命名方式）
         */
        public String generateDbName(Long appId) {
            if (appId == null) return dbNamePrefix + "0";
            return dbNamePrefix + appId;
        }

        /**
         * 生成 MongoDB 连接字符串（用于容器内环境变量）
         */
        public String getConnectionString(Long appId) {
            String dbName = generateDbName(appId);
            if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
                // URL 编码密码（特殊字符如 ! 需要编码）
                String encodedPassword = urlEncodePassword(password);
                return String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                        username, encodedPassword, host, port, dbName, authSource);
            } else {
                return String.format("mongodb://%s:%d/%s", host, port, dbName);
            }
        }

        private String urlEncodePassword(String pwd) {
            if (pwd == null) return "";
            try {
                return java.net.URLEncoder.encode(pwd, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return pwd;
            }
        }

        /**
         * Shell 单引号转义（用于 bash 脚本）
         */
        private String escapeShellSingleQuote(String s) {
            if (s == null) return "";
            // 在单引号字符串中，单引号需要结束当前字符串，添加转义的单引号，再开始新字符串
            return s.replace("'", "'\\''");
        }

        /**
         * 生成 Mongo 环境变量（用于注入到容器启动脚本）
         */
        public String generateMongoEnvVars(Long appId) {
            if (!enabled) return "";
            
            String dbName = generateDbName(appId);
            String mongoUrl = getConnectionString(appId);
            
            StringBuilder sb = new StringBuilder();
            sb.append("export MONGO_HOST='").append(escapeShellSingleQuote(host)).append("'\n");
            sb.append("export MONGO_PORT='").append(port).append("'\n");
            
            if (StringUtils.hasText(username)) {
                sb.append("export MONGO_USERNAME='").append(escapeShellSingleQuote(username)).append("'\n");
            }
            if (StringUtils.hasText(password)) {
                sb.append("export MONGO_PASSWORD='").append(escapeShellSingleQuote(password)).append("'\n");
            }
            if (StringUtils.hasText(authSource)) {
                sb.append("export MONGO_AUTH_SOURCE='").append(escapeShellSingleQuote(authSource)).append("'\n");
            }
            
            sb.append("export MONGO_DB_PREFIX='").append(escapeShellSingleQuote(dbNamePrefix)).append("'\n");
            sb.append("export MONGO_DB_NAME='").append(escapeShellSingleQuote(dbName)).append("'\n");
            sb.append("export MONGO_URL='").append(escapeShellSingleQuote(mongoUrl)).append("'\n");
            sb.append("export MONGODB_URI=\"$MONGO_URL\"\n");
            
            return sb.toString();
        }
    }
}
