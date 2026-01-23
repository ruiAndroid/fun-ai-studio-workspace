package fun.ai.studio.workspace;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * zip 工具：安全解压（防 Zip Slip）
 */
public final class ZipUtils {
    private ZipUtils() {}

    public static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (dir == null || Files.notExists(dir)) return;
        // 不吞异常：删除失败必须显式暴露，否则调用方会误以为已清理成功但磁盘仍残留
        // 默认不跟随软链（Files.walkFileTree 未指定 FOLLOW_LINKS）
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void unzipSafely(InputStream zipStream, Path destDir) throws IOException {
        unzipSafely(zipStream, destDir, Set.of());
    }

    /**
     * 安全解压 zip（防 Zip Slip），支持排除特定目录/文件
     * @param excludeNames 排除的目录/文件名（路径中任一 segment 命中则跳过，如 ".git"）
     */
    public static void unzipSafely(InputStream zipStream, Path destDir, Set<String> excludeNames) throws IOException {
        if (zipStream == null) {
            throw new IllegalArgumentException("zipStream 不能为空");
        }
        if (destDir == null) {
            throw new IllegalArgumentException("destDir 不能为空");
        }
        Set<String> excludes = (excludeNames == null) ? Set.of() : excludeNames;
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                // 跳过排除的目录/文件（如 .git）
                if (shouldExcludeEntry(name, excludes)) {
                    zis.closeEntry();
                    continue;
                }
                Path newPath = destDir.resolve(name).normalize();
                if (!newPath.startsWith(destDir)) {
                    throw new IOException("非法zip条目路径: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Path parent = newPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static boolean shouldExcludeEntry(String entryName, Set<String> excludes) {
        if (entryName == null || excludes == null || excludes.isEmpty()) return false;
        // 将路径按 / 分割，检查每个 segment
        String[] parts = entryName.split("/");
        for (String part : parts) {
            if (part != null && !part.isEmpty() && excludes.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将目录打包为 zip（相对路径写入 zip，避免路径穿越；不跟随软链）。
     */
    public static void zipDirectory(Path sourceDir, OutputStream out) throws IOException {
        zipDirectory(sourceDir, out, Set.of());
    }

    /**
     * 将目录打包为 zip（相对路径写入 zip，避免路径穿越；不跟随软链）。
     * @param excludeNames 排除的目录/文件名（若相对路径任一 segment 命中则跳过）
     */
    public static void zipDirectory(Path sourceDir, OutputStream out, Set<String> excludeNames) throws IOException {
        if (sourceDir == null || Files.notExists(sourceDir) || !Files.isDirectory(sourceDir)) {
            throw new IOException("sourceDir 不存在或不是目录: " + sourceDir);
        }
        if (out == null) {
            throw new IllegalArgumentException("out 不能为空");
        }
        Set<String> excludes = (excludeNames == null) ? Set.of() : excludeNames;
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            try (var walk = Files.walk(sourceDir)) {
                walk.forEach(p -> {
                    try {
                        if (Files.isDirectory(p)) {
                            return;
                        }
                        // 不跟随软链，避免越界
                        if (Files.isSymbolicLink(p)) {
                            return;
                        }
                        Path rel = sourceDir.relativize(p);
                        if (shouldExclude(rel, excludes)) {
                            return;
                        }
                        String entryName = rel.toString().replace("\\", "/");
                        if (entryName.isBlank()) return;
                        ZipEntry entry = new ZipEntry(entryName);
                        zos.putNextEntry(entry);
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException re) {
                if (re.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw re;
            }
            zos.finish();
        }
    }

    private static boolean shouldExclude(Path rel, Set<String> excludes) {
        if (rel == null || excludes == null || excludes.isEmpty()) return false;
        for (Path part : rel) {
            String n = part == null ? null : part.toString();
            if (n != null && excludes.contains(n)) {
                return true;
            }
        }
        return false;
    }
}


