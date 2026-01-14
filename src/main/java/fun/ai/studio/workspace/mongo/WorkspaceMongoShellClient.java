package fun.ai.studio.workspace.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.workspace.CommandResult;
import fun.ai.studio.workspace.CommandRunner;
import fun.ai.studio.workspace.WorkspaceProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Workspace Mongo Explorer（只读）：
 * 通过 docker exec 在用户容器内调用 mongosh，并输出 JSON（EJSON relaxed）供后端解析。
 *
 * 安全原则：
 * - 仅允许访问指定 dbName（通常为 dbNamePrefix + appId）
 * - 仅允许只读操作（listCollections/find/findOne）
 * - 不接受任意 JS eval（只接受结构化参数，由后端拼固定模板脚本）
 */
@Component
public class WorkspaceMongoShellClient {
    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(8);
    private static final int DEFAULT_MAX_TIME_MS = 3000;
    private static final long SHELL_CACHE_TTL_MS = 10 * 60 * 1000L; // 10min

    // 只允许“常见安全字符”的集合名，避免注入/路径穿越/奇怪字符导致的边界问题
    private static final Pattern SAFE_COLLECTION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$");

    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;
    private final WorkspaceProperties workspaceProperties;

    private final ConcurrentHashMap<String, CachedShell> shellCache = new ConcurrentHashMap<>();

    public WorkspaceMongoShellClient(CommandRunner commandRunner, ObjectMapper objectMapper, WorkspaceProperties workspaceProperties) {
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
        this.workspaceProperties = workspaceProperties;
    }

    public List<String> listCollections(String containerName, String dbName) {
        assertMongoEnabled();
        assertContainerName(containerName);
        assertDbName(dbName);
        MongoShell shell = getShellCached(containerName);

        String script = shell.isMongosh()
                ? ""
                + "const dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "const db2=db.getSiblingDB(dbName);\n"
                + "const cols=db2.getCollectionNames().sort();\n"
                + "print(EJSON.stringify({dbName:dbName,collections:cols},{relaxed:true}));\n"
                : ""
                + "var dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "var db2=db.getSiblingDB(dbName);\n"
                + "var cols=db2.getCollectionNames().sort();\n"
                + "print(JSON.stringify({dbName:dbName,collections:cols}));\n";

        Map<?, ?> r = runMongoShellJson(shell, containerName, dbName, script, true);
        Object cols = r == null ? null : r.get("collections");
        if (!(cols instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(x -> x != null)
                .map(String::valueOf)
                .toList();
    }

    public Map<String, Object> find(String containerName,
                                    String dbName,
                                    String collection,
                                    String filterJson,
                                    String projectionJson,
                                    String sortJson,
                                    Integer limit,
                                    Integer skip) {
        assertMongoEnabled();
        assertContainerName(containerName);
        assertDbName(dbName);
        assertCollectionName(collection);
        MongoShell shell = getShellCached(containerName);

        int lim = clamp(limit == null ? 50 : limit, 1, 200);
        int sk = clamp(skip == null ? 0 : skip, 0, 10000);

        String filter = StringUtils.hasText(filterJson) ? filterJson : "{}";
        String proj = StringUtils.hasText(projectionJson) ? projectionJson : "null";
        String sort = StringUtils.hasText(sortJson) ? sortJson : "null";

        String script = shell.isMongosh()
                ? ""
                + "const dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "const collName='" + jsSingleQuoted(collection) + "';\n"
                + "const db2=db.getSiblingDB(dbName);\n"
                + "const filter=EJSON.parse('" + jsSingleQuoted(filter) + "');\n"
                + "const projTxt='" + jsSingleQuoted(proj) + "';\n"
                + "const sortTxt='" + jsSingleQuoted(sort) + "';\n"
                + "const projection=(projTxt==='null'||projTxt==='')?null:EJSON.parse(projTxt);\n"
                + "const sortObj=(sortTxt==='null'||sortTxt==='')?null:EJSON.parse(sortTxt);\n"
                + "let cur=db2.getCollection(collName).find(filter, projection?{projection:projection}:undefined).maxTimeMS(" + DEFAULT_MAX_TIME_MS + ");\n"
                + "if(sortObj){cur=cur.sort(sortObj);} \n"
                + "cur=cur.skip(" + sk + ").limit(" + lim + ");\n"
                + "const items=cur.toArray();\n"
                + "print(EJSON.stringify({dbName:dbName,collection:collName,limit:" + lim + ",skip:" + sk + ",returned:items.length,items:items},{relaxed:true}));\n"
                : ""
                + "var dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "var collName='" + jsSingleQuoted(collection) + "';\n"
                + "var db2=db.getSiblingDB(dbName);\n"
                + "var filter=JSON.parse('" + jsSingleQuoted(filter) + "');\n"
                + "var projTxt='" + jsSingleQuoted(proj) + "';\n"
                + "var sortTxt='" + jsSingleQuoted(sort) + "';\n"
                + "var projection=(projTxt==='null'||projTxt==='')?null:JSON.parse(projTxt);\n"
                + "var sortObj=(sortTxt==='null'||sortTxt==='')?null:JSON.parse(sortTxt);\n"
                + "var cur=db2.getCollection(collName).find(filter, projection);\n"
                + "if(sortObj){cur=cur.sort(sortObj);} \n"
                + "cur=cur.skip(" + sk + ").limit(" + lim + ");\n"
                + "var items=cur.toArray();\n"
                + "print(JSON.stringify({dbName:dbName,collection:collName,limit:" + lim + ",skip:" + sk + ",returned:items.length,items:items}));\n";

        Map<?, ?> r = runMongoShellJson(shell, containerName, dbName, script, true);
        if (r == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(toStringObjectMap(r));
        return out;
    }

    public Map<String, Object> findOneById(String containerName, String dbName, String collection, String id) {
        assertMongoEnabled();
        assertContainerName(containerName);
        assertDbName(dbName);
        assertCollectionName(collection);
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("id 不能为空");
        MongoShell shell = getShellCached(containerName);

        String script = shell.isMongosh()
                ? ""
                + "const dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "const collName='" + jsSingleQuoted(collection) + "';\n"
                + "const idStr='" + jsSingleQuoted(id.trim()) + "';\n"
                + "const db2=db.getSiblingDB(dbName);\n"
                + "let _id;\n"
                + "try{ _id=new ObjectId(idStr); }catch(e){ _id=idStr; }\n"
                + "const doc=db2.getCollection(collName).findOne({_id:_id},{maxTimeMS:" + DEFAULT_MAX_TIME_MS + "});\n"
                + "print(EJSON.stringify({dbName:dbName,collection:collName,id:idStr,doc:doc},{relaxed:true}));\n"
                : ""
                + "var dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "var collName='" + jsSingleQuoted(collection) + "';\n"
                + "var idStr='" + jsSingleQuoted(id.trim()) + "';\n"
                + "var db2=db.getSiblingDB(dbName);\n"
                + "var _id;\n"
                + "try{ _id=ObjectId(idStr); }catch(e){ _id=idStr; }\n"
                + "var doc=db2.getCollection(collName).findOne({_id:_id});\n"
                + "print(JSON.stringify({dbName:dbName,collection:collName,id:idStr,doc:doc}));\n";

        Map<?, ?> r = runMongoShellJson(shell, containerName, dbName, script, true);
        if (r == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(toStringObjectMap(r));
        return out;
    }

    private Map<?, ?> runMongoShellJson(MongoShell shell, String containerName, String dbName, String script, boolean allowRetry) {
        // 连接串用 localhost，避免依赖容器网络/端口映射
        String uri = "mongodb://127.0.0.1:" + mongoPort() + "/" + dbName;
        List<String> cmd = List.of(
                "docker", "exec", "-i", containerName,
                shell.bin, uri,
                "--quiet",
                "--eval", script
        );
        CommandResult r = commandRunner.run(CMD_TIMEOUT, cmd);
        if (!r.isSuccess()) {
            // 若容器被重建/镜像升级导致 shell 变化，尝试清理缓存并重试一次（避免每次都 detectShell）
            String out = r.getOutput() == null ? "" : r.getOutput();
            String lower = out.toLowerCase();
            if (allowRetry && (lower.contains("executable file not found")
                    || lower.contains("not found")
                    || lower.contains("no such file or directory"))) {
                shellCache.remove(containerName);
                MongoShell retryShell = getShellCached(containerName);
                return runMongoShellJson(retryShell, containerName, dbName, script, false);
            }
            throw new IllegalStateException(shell.bin + " 执行失败（exitCode=" + r.getExitCode() + "）："
                    + truncate(out, 500));
        }
        // podman-docker 可能会把告警打印到 stdout，取最后一行非空输出作为 JSON
        String out = lastNonEmptyLine(r.getOutput());
        if (!StringUtils.hasText(out)) {
            throw new IllegalStateException(shell.bin + " 无输出：请确认容器内 mongod 正常运行");
        }
        // CommandRunner 最多抓 32KB，若 JSON 被截断，这里会解析失败并给出可操作提示
        try {
            return objectMapper.readValue(out, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析 " + shell.bin + " 输出失败（可能输出过大被截断/或被 podman 告警干扰）：请降低 limit 或使用 projection。原始输出片段："
                    + truncate(out, 500));
        }
    }

    private MongoShell detectShell(String containerName) {
        // 优先 mongosh，其次 mongo（老版本 shell）；都没有则给出可操作报错
        List<String> cmd = List.of(
                "docker", "exec", "-i", containerName,
                "bash", "-lc",
                "if command -v mongosh >/dev/null 2>&1; then echo mongosh; " +
                        "elif command -v mongo >/dev/null 2>&1; then echo mongo; " +
                        "else echo none; fi"
        );
        CommandResult r = commandRunner.run(Duration.ofSeconds(3), cmd);
        String out = lastNonEmptyLine(r.getOutput());
        String bin = (out == null ? "" : out.trim());
        if ("mongosh".equals(bin)) return new MongoShell("mongosh", true);
        if ("mongo".equals(bin)) return new MongoShell("mongo", false);
        throw new IllegalStateException("容器镜像未包含 mongosh/mongo：请使用包含 Mongo 工具链的 workspace image（推荐 mongosh），" +
                "或在镜像构建时安装 mongosh（Debian/Ubuntu 常见做法：apt-get install -y mongodb-mongosh 或 mongosh）。");
    }

    private MongoShell getShellCached(String containerName) {
        long now = System.currentTimeMillis();
        CachedShell c = shellCache.get(containerName);
        if (c != null && now - c.cachedAtMs <= SHELL_CACHE_TTL_MS && c.shell != null) {
            return c.shell;
        }
        MongoShell shell = detectShell(containerName);
        shellCache.put(containerName, new CachedShell(shell, now));
        return shell;
    }

    private void assertMongoEnabled() {
        if (workspaceProperties == null || workspaceProperties.getMongo() == null || !workspaceProperties.getMongo().isEnabled()) {
            throw new IllegalStateException("Workspace Mongo 未启用：请设置 funai.workspace.mongo.enabled=true");
        }
    }

    private int mongoPort() {
        if (workspaceProperties == null || workspaceProperties.getMongo() == null) return 27017;
        int p = workspaceProperties.getMongo().getPort();
        return p > 0 ? p : 27017;
    }

    private void assertContainerName(String containerName) {
        if (!StringUtils.hasText(containerName)) throw new IllegalArgumentException("containerName 不能为空");
    }

    private void assertDbName(String dbName) {
        if (!StringUtils.hasText(dbName)) throw new IllegalArgumentException("dbName 不能为空");
        if (dbName.length() > 128) throw new IllegalArgumentException("dbName 过长");
    }

    private void assertCollectionName(String collection) {
        if (!StringUtils.hasText(collection)) throw new IllegalArgumentException("collection 不能为空");
        String c = collection.trim();
        if (!SAFE_COLLECTION.matcher(c).matches()) {
            throw new IllegalArgumentException("collection 非法（仅允许字母/数字/._-，且长度<=120）");
        }
        if (c.startsWith("system.")) {
            throw new IllegalArgumentException("禁止访问 system.* 集合");
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 逃逸为 JS 单引号字符串内容（用于 mongosh --eval 内部拼接）。
     */
    private static String jsSingleQuoted(String s) {
        if (s == null) return "";
        return s
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated)";
    }

    private static String lastNonEmptyLine(String output) {
        if (output == null) return "";
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (m == null) return out;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = e.getKey() == null ? null : String.valueOf(e.getKey());
            out.put(k, e.getValue());
        }
        return out;
    }

    private static final class MongoShell {
        private final String bin;
        private final boolean mongosh;

        private MongoShell(String bin, boolean mongosh) {
            this.bin = bin;
            this.mongosh = mongosh;
        }

        private boolean isMongosh() {
            return mongosh;
        }
    }

    private static final class CachedShell {
        private final MongoShell shell;
        private final long cachedAtMs;

        private CachedShell(MongoShell shell, long cachedAtMs) {
            this.shell = shell;
            this.cachedAtMs = cachedAtMs;
        }
    }
}


