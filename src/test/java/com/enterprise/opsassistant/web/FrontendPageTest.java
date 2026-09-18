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
        mockMvc.perform(get("/index.html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"ai-stream\"")));
    }

    /** 页面依赖的静态文件必须能被同一个 Spring Boot 应用访问。 */
    @Test
    void shouldServeFrontendAssets() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));

        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                // Spring Boot 3.5 按新的媒体类型注册表为 .js 返回 text/javascript。
                // 这是浏览器认可的标准 JavaScript 类型，不影响脚本加载和执行。
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/api/v1/alerts/analyze/stream")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "updateReportSection")))
                // MockMvc 对没有 charset 参数的 JavaScript 响应可能按 ISO-8859-1 解码，
                // 因此这里验证稳定的 ASCII 结构标记；中文文案由真实浏览器端到端测试覆盖。
                // reasoning-list 代表最终报告确实包含“分析推理与 AI 复核”区块。
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "reasoning-list")))
                // ActionUrgency 的两个枚举分支必须都进入前端映射表，不能直接回显原始值。
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "SHORT_TERM:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "OBSERVATION:")));
    }
}
