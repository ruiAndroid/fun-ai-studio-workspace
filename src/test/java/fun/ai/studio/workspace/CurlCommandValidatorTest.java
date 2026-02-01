package fun.ai.studio.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CurlCommandValidator 单元测试
 */
class CurlCommandValidatorTest {

    private CurlCommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CurlCommandValidator();
    }

    @Test
    void testValidCurlCommand() {
        // 有效的 curl 命令应该通过验证
        assertDoesNotThrow(() -> validator.validate("curl http://localhost:5173/api/test"));
        assertDoesNotThrow(() -> validator.validate("curl -X POST http://localhost:5173/api/test"));
        assertDoesNotThrow(() -> validator.validate("curl -H 'Content-Type: application/json' http://localhost:5173"));
        assertDoesNotThrow(() -> validator.validate("CURL http://localhost:5173")); // 不区分大小写
        assertDoesNotThrow(() -> validator.validate("  curl http://localhost:5173  ")); // 前后空格
    }

    @Test
    void testEmptyCommand() {
        // 空命令应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(""));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("   "));
    }

    @Test
    void testNonCurlCommand() {
        // 不以 curl 开头的命令应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> validator.validate("wget http://localhost:5173"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ls -la"));
    }

    @Test
    void testDangerousShellMetacharacters() {
        // 包含危险 shell 元字符的命令应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173 | bash"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173 && echo 'hacked'"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173 > /etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173 < /etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl `whoami`"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl $(whoami)"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl $HOME"));
    }

    @Test
    void testDangerousFileOperations() {
        // 包含危险文件操作的命令应该抛出异常（绝对路径）
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173 -o /etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173 -O /tmp/file"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("curl http://localhost:5173 --output /var/log/test"));
        
        // 相对路径应该允许
        assertDoesNotThrow(() -> validator.validate("curl http://localhost:5173 -o output.txt"));
        assertDoesNotThrow(() -> validator.validate("curl http://localhost:5173 -O"));
        assertDoesNotThrow(() -> validator.validate("curl http://localhost:5173 --output result.json"));
    }

    @Test
    void testComplexValidCommands() {
        // 复杂但有效的 curl 命令
        assertDoesNotThrow(() -> validator.validate("curl -X POST -H 'Content-Type: application/json' -d '{\"key\":\"value\"}' http://localhost:5173/api/test"));
        assertDoesNotThrow(() -> validator.validate("curl -v -L --max-time 10 http://localhost:5173"));
        assertDoesNotThrow(() -> validator.validate("curl --connect-timeout 5 --retry 3 http://localhost:5173"));
    }
}
