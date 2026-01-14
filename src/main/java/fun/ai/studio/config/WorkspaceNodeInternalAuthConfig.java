package fun.ai.studio.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 workspace-node 内部鉴权 filter。
 */
@Configuration
public class WorkspaceNodeInternalAuthConfig {

    @Bean
    public FilterRegistrationBean<WorkspaceNodeInternalAuthFilter> workspaceNodeInternalAuthFilterRegistration(
            WorkspaceNodeInternalAuthProperties props
    ) {
        FilterRegistrationBean<WorkspaceNodeInternalAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new WorkspaceNodeInternalAuthFilter(props));
        reg.addUrlPatterns("/api/fun-ai/workspace/*");
        reg.setOrder(1);
        return reg;
    }
}


