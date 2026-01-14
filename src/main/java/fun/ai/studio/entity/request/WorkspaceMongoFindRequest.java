package fun.ai.studio.entity.request;

import lombok.Data;

/**
 * Mongo Explorer（只读）查询请求：
 * 由后端拼出固定模板的 mongosh 脚本执行，避免用户提交任意 JS。
 */
@Data
public class WorkspaceMongoFindRequest {
    private String collection;
    /**
     * JSON 字符串（EJSON relaxed 也可）：例如 {"name":"tom"} / {}。
     */
    private String filter;
    /**
     * JSON 字符串：例如 {"password":0}；可为空。
     */
    private String projection;
    /**
     * JSON 字符串：例如 {"createdAt":-1}；可为空。
     */
    private String sort;
    /**
     * 1~200，默认 50
     */
    private Integer limit;
    /**
     * 0~10000，默认 0
     */
    private Integer skip;
}


