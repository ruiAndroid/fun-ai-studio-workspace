package fun.ai.studio.workspace;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Curl 命令安全验证器
 * 防止命令注入和危险操作
 */
@Component
public class CurlCommandValidator {

    // 危险的 shell 元字符
    private static final Pattern DANGEROUS_CHARS = Pattern.compile("[;|&><`$]|\\$\\(");
    
    // 危险的文件操作：-o 或 -O 或 --output 后跟绝对路径
    private static final Pattern DANGEROUS_FILE_OPS = Pattern.compile(
        "(?:^|\\s)(?:-o|-O|--output)\\s+/"
    );

    /**
     * 验证 curl 命令是否安全
     * 
     * @param curlCommand curl 命令字符串
     * @throws IllegalArgumentException 如果命令不安全
     */
    public void validate(String curlCommand) {
        if (curlCommand == null || curlCommand.isBlank()) {
            throw new IllegalArgumentException("curl command cannot be empty");
        }

        String trimmed = curlCommand.trim();
        
        // 检查命令是否以 "curl" 开头（不区分大小写）
        if (!trimmed.toLowerCase().startsWith("curl")) {
            throw new IllegalArgumentException("command must start with 'curl'");
        }

        // 检查是否包含危险的 shell 元字符
        if (containsDangerousChars(curlCommand)) {
            throw new IllegalArgumentException("curl command contains dangerous shell metacharacters");
        }

        // 检查是否包含危险的文件操作
        if (containsDangerousFileOps(curlCommand)) {
            throw new IllegalArgumentException("curl command contains dangerous file operations with absolute paths");
        }
    }

    /**
     * 检查是否包含危险的 shell 元字符
     */
    private boolean containsDangerousChars(String command) {
        return DANGEROUS_CHARS.matcher(command).find();
    }

    /**
     * 检查是否包含危险的文件操作
     */
    private boolean containsDangerousFileOps(String command) {
        return DANGEROUS_FILE_OPS.matcher(command).find();
    }
}
