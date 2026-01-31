# 应用删除时目录清理逻辑修复

## 问题描述

删除应用时，清理应用目录和 run 日志可能失败，导致应用删除流程被阻塞。

## 原因分析

在 `FunAiWorkspaceServiceImpl.cleanupWorkspaceOnAppDeleted()` 方法中：

1. `cleanupRunLogsForApp()` - 清理 run 日志时，如果失败会抛出异常（第 2007 行）
2. `deleteDirBestEffort()` - 清理应用目录时，如果删除和隔离都失败会抛出异常（第 2066 行）

这两个异常会阻塞应用删除流程，导致数据库中的应用记录无法删除。

## 解决方案

将这两个清理操作改为 best-effort 模式：

1. 在调用 `cleanupRunLogsForApp()` 时加上 try-catch，失败时只记录 warn 日志
2. 在调用 `deleteDirBestEffort()` 时加上 try-catch，失败时只记录 warn 日志

**注意**：不修改 `deleteDirBestEffort()` 内部的重试 3 次和隔离机制，保持原样。

## 修改内容

### 文件：`FunAiWorkspaceServiceImpl.java`

**修改前**：
```java
// 3) 删除该 appId 对应的历史 run 日志
cleanupRunLogsForApp(hostRunDir, userId, appId);

// 3) 删除 workspace app 目录
deleteDirBestEffort(hostAppDir, userId, appId);
```

**修改后**：
```java
// 3) 删除该 appId 对应的历史 run 日志
try {
    cleanupRunLogsForApp(hostRunDir, userId, appId);
} catch (Exception e) {
    // 清理 run 日志失败不阻断应用删除流程
    log.warn("cleanup run logs failed (best-effort): userId={}, appId={}, err={}", userId, appId, e.getMessage());
}

// 3) 删除 workspace app 目录
try {
    deleteDirBestEffort(hostAppDir, userId, appId);
} catch (Exception e) {
    // 清理应用目录失败不阻断应用删除流程
    log.warn("cleanup workspace app dir failed (best-effort): userId={}, appId={}, dir={}, err={}", userId, appId, hostAppDir, e.getMessage());
}
```

## 效果

- 应用删除流程不会因为目录清理失败而被阻塞
- 清理失败时会记录 warn 日志，便于后续排查
- `deleteDirBestEffort()` 内部的重试 3 次和隔离机制保持不变
- 与 MongoDB 删除逻辑保持一致（也是 best-effort 模式）

## 测试建议

1. 删除一个正常应用，验证目录和日志正常清理
2. 删除一个应用目录被占用的应用（如 node_modules 正在被访问），验证应用删除成功但目录被隔离
3. 删除一个应用目录权限异常的应用，验证应用删除成功但记录 warn 日志
