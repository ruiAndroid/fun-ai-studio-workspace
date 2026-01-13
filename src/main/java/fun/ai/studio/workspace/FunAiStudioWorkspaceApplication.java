package fun.ai.studio.workspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FunAiStudioWorkspaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FunAiStudioWorkspaceApplication.class, args);
    }
}


