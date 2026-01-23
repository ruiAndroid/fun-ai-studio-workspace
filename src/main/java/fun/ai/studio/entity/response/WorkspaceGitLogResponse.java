package fun.ai.studio.entity.response;

import java.util.List;

/**
 * /api/fun-ai/workspace/git/log 响应
 */
public class WorkspaceGitLogResponse {
    /**
     * 提交记录列表（最近在前）
     */
    private List<GitCommitItem> commits;
    /**
     * 当前分支
     */
    private String branch;
    /**
     * 提示信息
     */
    private String message;

    public List<GitCommitItem> getCommits() {
        return commits;
    }

    public void setCommits(List<GitCommitItem> commits) {
        this.commits = commits;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public static class GitCommitItem {
        /**
         * commit SHA（完整）
         */
        private String sha;
        /**
         * commit SHA（短）
         */
        private String shaShort;
        /**
         * 提交信息
         */
        private String message;
        /**
         * 作者
         */
        private String author;
        /**
         * 提交时间（ISO 格式）
         */
        private String date;

        public String getSha() {
            return sha;
        }

        public void setSha(String sha) {
            this.sha = sha;
        }

        public String getShaShort() {
            return shaShort;
        }

        public void setShaShort(String shaShort) {
            this.shaShort = shaShort;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }
    }
}

