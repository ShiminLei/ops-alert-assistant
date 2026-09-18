package com.enterprise.opsassistant.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Spring Boot 能直接提供前端入口与静态资源。
 *
 * <p>这里不重复测试浏览器脚本内部逻辑；页面交互由浏览器端测试负责。本测试关注 Java 应用
 * 打包后是否仍包含首页、样式和脚本，避免本地文件存在但没有进入最终 JAR。</p>
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class FrontendPageTest {

    @Autowired
    private MockMvc mockMvc;

    /** 根路径由 Spring 欢迎页机制内部转发到静态首页，不要求用户输入 index.html。 */
    @Test
    void shouldServeOperationsWorkspaceAtRootPath() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        // 直接请求转发目标，验证真正打包进应用的 HTML 内容和资源引用。
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<meta charset=\"UTF-8\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "OPS SENTINEL")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"analysis-form\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "src=\"js/app.js\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"runtime-warning\"")));
    }

    /** 页面依赖的静态文件必须能被同一个 Spring Boot 应用访问。 */
    @Test
    void shouldServeFrontendAssets() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));

        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/javascript"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/api/v1/alerts/analyze/stream")));
    }
}
