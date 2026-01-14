package fun.ai.studio.workspace;

public class CommandResult {
    private final int exitCode;
    private final String output;

    public CommandResult(int exitCode, String output) {
        this.exitCode = exitCode;
        this.output = output;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }
}


