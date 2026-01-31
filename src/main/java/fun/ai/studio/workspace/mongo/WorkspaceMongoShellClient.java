package fun.ai.studio.workspace.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.workspace.CommandResult;
import fun.ai.studio.workspace.CommandRunner;
import fun.ai.studio.workspace.WorkspaceProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Workspace Mongo Explorer（只读）：
 * 直接从 87 服务器连接到 88 独立 MongoDB 服务器，并输出 JSON（EJSON relaxed）供后端解析。
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

    // 只允许"常见安全字符"的集合名，避免注入/路径穿越/奇怪字符导致的边界问题
    private static final Pattern SAFE_COLLECTION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$");

    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;
    private final WorkspaceProperties workspaceProperties;

    public WorkspaceMongoShellClient(CommandRunner commandRunner, ObjectMapper objectMapper, WorkspaceProperties workspaceProperties) {
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
        this.workspaceProperties = workspaceProperties;
    }

    public List<String> listCollections(String containerName, String dbName) {
        assertMongoEnabled();
        assertDbName(dbName);

        String script = ""
                + "const dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "const db2=db.getSiblingDB(dbName);\n"
                + "const cols=db2.getCollectionNames().sort();\n"
                + "print(EJSON.stringify({dbName:dbName,collections:cols},{relaxed:true}));\n";

        Map<?, ?> r = runMongoShellJson(dbName, script);
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
        assertDbName(dbName);
        assertCollectionName(collection);

        int lim = clamp(limit == null ? 50 : limit, 1, 200);
        int sk = clamp(skip == null ? 0 : skip, 0, 10000);

        String filter = StringUtils.hasText(filterJson) ? filterJson : "{}";
        String proj = StringUtils.hasText(projectionJson) ? projectionJson : "null";
        String sort = StringUtils.hasText(sortJson) ? sortJson : "null";

        String script = ""
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
                + "print(EJSON.stringify({dbName:dbName,collection:collName,limit:" + lim + ",skip:" + sk + ",returned:items.length,items:items},{relaxed:true}));\n";

        Map<?, ?> r = runMongoShellJson(dbName, script);
        if (r == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(toStringObjectMap(r));
        return out;
    }

    public Map<String, Object> findOneById(String containerName, String dbName, String collection, String id) {
        assertMongoEnabled();
        assertDbName(dbName);
        assertCollectionName(collection);
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("id 不能为空");

        String script = ""
                + "const dbName='" + jsSingleQuoted(dbName) + "';\n"
                + "const collName='" + jsSingleQuoted(collection) + "';\n"
                + "const idStr='" + jsSingleQuoted(id.trim()) + "';\n"
                + "const db2=db.getSiblingDB(dbName);\n"
                + "let _id;\n"
                + "try{ _id=new ObjectId(idStr); }catch(e){ _id=idStr; }\n"
                + "const doc=db2.getCollection(collName).findOne({_id:_id},{maxTimeMS:" + DEFAULT_MAX_TIME_MS + "});\n"
                + "print(EJSON.stringify({dbName:dbName,collection:collName,id:idStr,doc:doc},{relaxed:true}));\n";

        Map<?, ?> r = runMongoShellJson(dbName, script);
        if (r == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(toStringObjectMap(r));
        return out;
    }

    /**
     * 删除指定数据库（用于应用删除时清理）
     * 注意：此操作不可逆，需要调用方确保已做权限校验
     * 
     * 此方法直接从 workspace 服务器连接到 MongoDB 服务器，不依赖用户容器
     */
    public boolean dropDatabase(String dbName) {
        assertMongoEnabled();
        assertDbName(dbName);
        
        // 安全检查：只允许删除 workspace 数据库（db_ 前缀）
        if (!dbName.startsWith(workspaceProperties.getMongo().getDbNamePrefix())) {
            throw new IllegalArgumentException("只允许删除 workspace 数据库（" + workspaceProperties.getMongo().getDbNamePrefix() + "* 前缀）");
        }
        
        // 直接从 87 服务器连接到 88 服务器的 MongoDB
        WorkspaceProperties.MongoProperties mongoCfg = workspaceProperties.getMongo();
        String host = mongoCfg.getHost();
        int port = mongoCfg.getPort();
        String username = mongoCfg.getUsername();
        String password = mongoCfg.getPassword();
        String authSource = mongoCfg.getAuthSource();
        
        // 构建连接字符串
        String uri;
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            uri = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                    username, password, host, port, dbName, authSource);
        } else {
            uri = String.format("mongodb://%s:%d/%s", host, port, dbName);
        }
        
        // 使用 mongosh 命令行工具直接连接（不依赖容器）
        String script = "db.dropDatabase()";
        List<String> cmd = List.of(
                "mongosh", uri,
                "--quiet",
                "--eval", script
        );
        
        try {
            CommandResult r = commandRunner.run(Duration.ofSeconds(10), cmd);
            if (!r.isSuccess()) {
                throw new RuntimeException("删除数据库失败: exitCode=" + r.getExitCode() + ", output=" + r.getOutput());
            }
            
            // 解析输出，检查是否成功
            String out = lastNonEmptyLine(r.getOutput());
            if (StringUtils.hasText(out)) {
                try {
                    Map<?, ?> result = objectMapper.readValue(out, Map.class);
                    Object ok = result.get("ok");
                    return ok != null && (ok.equals(1) || ok.equals(1.0) || "1".equals(ok.toString()));
                } catch (Exception e) {
                    // 如果解析失败，检查输出中是否包含成功标识
                    return out.contains("\"ok\"") && (out.contains(":1") || out.contains(": 1"));
                }
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("删除数据库失败: " + dbName + ", error: " + e.getMessage(), e);
        }
    }

    /**
     * 直接从 87 服务器连接到 88 MongoDB 服务器执行查询（不依赖用户容器）
     */
    private Map<?, ?> runMongoShellJson(String dbName, String script) {
        WorkspaceProperties.MongoProperties mongoCfg = workspaceProperties.getMongo();
        String host = mongoCfg.getHost();
        int port = mongoCfg.getPort();
        String username = mongoCfg.getUsername();
        String password = mongoCfg.getPassword();
        String authSource = mongoCfg.getAuthSource();
        
        // 构建连接字符串
        String uri;
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            uri = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                    username, password, host, port, dbName, authSource);
        } else {
            uri = String.format("mongodb://%s:%d/%s", host, port, dbName);
        }
        
        // 直接在 87 服务器上执行 mongosh（不通过 docker exec）
        List<String> cmd = List.of(
                "mongosh", uri,
                "--quiet",
                "--eval", script
        );
        
        CommandResult r = commandRunner.run(CMD_TIMEOUT, cmd);
        if (!r.isSuccess()) {
            String out = r.getOutput() == null ? "" : r.getOutput();
            throw new IllegalStateException("mongosh 执行失败（exitCode=" + r.getExitCode() + "）："
                    + truncate(out, 500));
        }
        
        // 取最后一行非空输出作为 JSON
        String out = lastNonEmptyLine(r.getOutput());
        if (!StringUtils.hasText(out)) {
            throw new IllegalStateException("mongosh 无输出：请确认 MongoDB 服务器正常运行且网络可达");
        }
        
        // 解析 JSON 输出
        try {
            return objectMapper.readValue(out, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析 mongosh 输出失败（可能输出过大被截断）：请降低 limit 或使用 projection。原始输出片段："
                    + truncate(out, 500));
        }
    }

    private void assertMongoEnabled() {
        if (workspaceProperties == null || workspaceProperties.getMongo() == null || !workspaceProperties.getMongo().isEnabled()) {
            throw new IllegalStateException("Workspace Mongo 未启用：请设置 funai.workspace.mongo.enabled=true");
        }
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
}
