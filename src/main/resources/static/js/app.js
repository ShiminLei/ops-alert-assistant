/**
 * 告警助手前端控制器。
 *
 * <p>页面不依赖前端框架：浏览器直接调用 Spring Boot SSE 接口，并把阶段事件映射成进度，
 * 把最终 IncidentReport 映射成适合人阅读的报告。所有来自接口和用户输入的文本在进入
 * innerHTML 前都会执行转义，避免告警内容被浏览器当成 HTML 执行。</p>
 */
(() => {
    "use strict";

    /** SupervisorAgent 的固定阶段顺序，同时决定进度条百分比和中文展示名称。 */
    const STAGES = [
        ["RECEIVED", "接收告警"],
        ["ALERT_RECOGNIZED", "识别告警"],
        ["TOOLS_PLANNED", "规划工具"],
        ["EVIDENCE_COLLECTED", "收集证据"],
        ["ROOT_CAUSE_ANALYZED", "分析根因"],
        ["AI_REVIEWED", "AI 复核"],
        ["REPORT_GENERATED", "生成报告"],
        ["COMPLETED", "分析完成"]
    ];

    /** 页面运行状态集中存放，避免会话编号散落在不同 DOM 节点中成为多个数据来源。 */
    const state = {
        conversationId: null,
        latestAlert: "",
        latestReport: null,
        analyzing: false,
        // id 校验整条连接的顺序；Map 分别记录每个 runId 下一条应到达的 seq。
        lastSseId: 0,
        nextRunSequences: new Map()
    };

    const elements = {
        form: document.querySelector("#analysis-form"),
        alertText: document.querySelector("#alert-text"),
        runtimeWarning: document.querySelector("#runtime-warning"),
        characterCount: document.querySelector("#character-count"),
        submitButton: document.querySelector("#submit-button"),
        buttonLabel: document.querySelector("#submit-button .button-label"),
        newConversation: document.querySelector("#new-conversation"),
        conversationCard: document.querySelector("#conversation-card"),
        conversationId: document.querySelector("#conversation-id"),
        downloadReport: document.querySelector("#download-report"),
        emptyState: document.querySelector("#empty-state"),
        progressView: document.querySelector("#progress-view"),
        progressMessage: document.querySelector("#progress-message"),
        progressPercent: document.querySelector("#progress-percent"),
        progressBar: document.querySelector("#progress-bar"),
        stageList: document.querySelector("#stage-list"),
        aiStream: document.querySelector("#ai-stream"),
        aiStreamProvider: document.querySelector("#ai-stream-provider"),
        aiStreamContent: document.querySelector("#ai-stream-content"),
        reportView: document.querySelector("#report-view"),
        errorBanner: document.querySelector("#error-banner")
    };

    /** 初始化固定阶段列表，后续事件到达时只切换 class，不反复创建节点。 */
    function initializeStageList() {
        elements.stageList.replaceChildren(...STAGES.map(([stage, label]) => {
            const item = document.createElement("li");
            item.dataset.stage = stage;
            item.textContent = label;
            return item;
        }));
    }

    /** 更新字数提示，让用户在提交前知道后端 4000 字符限制。 */
    function updateCharacterCount() {
        elements.characterCount.textContent = `${elements.alertText.value.length} / 4000`;
    }

    /**
     * 切换分析中状态时同时锁定输入和按钮，防止一次会话并发提交导致历史消息顺序不确定。
     */
    function setAnalyzing(analyzing) {
        state.analyzing = analyzing;
        elements.alertText.disabled = analyzing;
        elements.submitButton.disabled = analyzing;
        elements.buttonLabel.textContent = analyzing ? "正在分析，请稍候…" :
            (state.conversationId ? "继续分析" : "开始智能分析");
    }

    /** 清理上一条错误；新错误会使用 role=alert 自动通知辅助技术。 */
    function clearError() {
        elements.errorBanner.hidden = true;
        elements.errorBanner.textContent = "";
    }

    function showError(message) {
        elements.errorBanner.textContent = message || "分析失败，请稍后重试。";
        elements.errorBanner.hidden = false;
        elements.progressView.hidden = true;
        if (!state.latestReport) {
            elements.emptyState.hidden = false;
        }
    }

    /** 每次提交都重置阶段状态，但保留当前会话和上一份报告数据。 */
    function showProgress() {
        clearError();
        elements.emptyState.hidden = true;
        elements.progressView.hidden = false;
        initializeLiveReport();
        elements.reportView.hidden = false;
        elements.downloadReport.hidden = true;
        elements.progressMessage.textContent = "正在建立分析任务…";
        elements.progressPercent.textContent = "0%";
        elements.progressBar.style.width = "0%";
        elements.aiStream.hidden = true;
        elements.aiStreamProvider.textContent = "等待模型响应";
        elements.aiStreamContent.textContent = "";
        state.lastSseId = 0;
        state.nextRunSequences.clear();
        elements.stageList.querySelectorAll("li").forEach(item => {
            item.classList.remove("active", "complete");
        });
    }

    /**
     * 展示 Spring AI 原生流式事件。
     * START 表示一次全新尝试，可能来自重试或备用模型，因此必须先清空旧片段；DELTA 才追加。
     */
    function updateAiStream(event) {
        const source = `${event.provider || "unknown"} / ${event.model || "unknown"}`;
        if (event.phase === "START") {
            elements.aiStream.hidden = false;
            elements.aiStreamContent.textContent = "";
            elements.aiStreamProvider.textContent = event.fallbackUsed
                ? `${source} · 备用模型接管`
                : `${source} · 正在生成`;
        } else if (event.phase === "DELTA") {
            elements.aiStream.hidden = false;
            // textContent 不会执行模型可能生成的 HTML、脚本或事件属性。
            elements.aiStreamContent.textContent += event.delta || "";
            elements.aiStreamContent.scrollTop = elements.aiStreamContent.scrollHeight;
        } else if (event.phase === "COMPLETE") {
            elements.aiStreamProvider.textContent = `${source} · 结构化转换完成`;
        }
    }

    /** 根据后端阶段更新进度；未知阶段不会破坏页面，只显示其消息。 */
    function updateProgress(event) {
        const currentIndex = STAGES.findIndex(([stage]) => stage === event.stage);
        elements.progressMessage.textContent = event.message || "正在分析…";
        if (currentIndex < 0) {
            return;
        }

        const percent = Math.round(((currentIndex + 1) / STAGES.length) * 100);
        elements.progressPercent.textContent = `${percent}%`;
        elements.progressBar.style.width = `${percent}%`;
        elements.stageList.querySelectorAll("li").forEach((item, index) => {
            item.classList.toggle("complete", index < currentIndex);
            item.classList.toggle("active", index === currentIndex);
        });
    }

    /**
     * 为本轮分析创建固定的报告区域。后续 SSE 只填充对应容器，不重建整个页面。
     * hidden 区段在第一条有效业务数据到达时才显示，避免先出现一排空卡片。
     */
    function initializeLiveReport() {
        elements.reportView.innerHTML = `
            <div id="live-report-status" class="helper-text">报告内容会随着分析结果逐块出现。</div>
            <section id="live-recognition" class="report-section" hidden></section>
            <section id="live-evidence" class="report-section" hidden>
                <h3>运维工具证据</h3><div id="live-evidence-grid" class="evidence-grid"></div>
            </section>
            <section id="live-root-cause" class="report-section" hidden></section>
            <section id="live-actions" class="report-section" hidden>
                <h3>建议处置动作</h3><ol id="live-action-list" class="action-list"></ol>
            </section>
            <section id="live-ai-review" class="report-section" hidden></section>`;
    }

    /** 根据区段事件增量填充报告；所有外部文本在进入模板前都经过 escapeHtml。 */
    function updateReportSection(type, data) {
        const status = document.querySelector("#live-report-status");
        if (status) {
            status.hidden = true;
        }

        if (type === "recognition") {
            const section = document.querySelector("#live-recognition");
            const risk = String(data.initialRisk || "UNKNOWN").toLowerCase();
            section.innerHTML = `
                <div class="report-summary">
                    <div>
                        <p class="eyebrow">INCIDENT RECOGNIZED</p>
                        <h3>${escapeHtml(data.serviceName)} · ${escapeHtml(data.alertType)}</h3>
                        <p>${escapeHtml(data.summary)}</p>
                    </div>
                    <span class="risk-badge risk-${escapeHtml(risk)}">${escapeHtml(riskLabel(data.initialRisk))}</span>
                </div>`;
            section.hidden = false;
        } else if (type === "evidence") {
            const section = document.querySelector("#live-evidence");
            const grid = document.querySelector("#live-evidence-grid");
            grid.insertAdjacentHTML("beforeend", `
                <div class="data-card">
                    <header><span>${escapeHtml(data.toolName)}</span><span>${escapeHtml(data.status)} · ${escapeHtml(data.durationMs)} ms</span></header>
                    <strong>${escapeHtml(data.summary)}</strong>
                    <p>证据编号：${escapeHtml(data.evidenceId)}</p>
                </div>`);
            section.hidden = false;
        } else if (type === "root-cause") {
            const section = document.querySelector("#live-root-cause");
            const causes = safeList(data.candidates).map(candidate => `
                <div class="data-card">
                    <header><span>候选根因</span><span class="confidence">${Math.round(Number(candidate.confidence || 0) * 100)}%</span></header>
                    <strong>${escapeHtml(candidate.description)}</strong>
                    <p>证据：${safeList(candidate.evidenceIds).map(escapeHtml).join("、") || "无"}</p>
                </div>`).join("");
            section.innerHTML = `<h3>根因候选 · ${escapeHtml(riskLabel(data.finalRisk))}</h3><div class="cause-grid">${causes}</div>`;
            section.hidden = false;
        } else if (type === "action") {
            const section = document.querySelector("#live-actions");
            const list = document.querySelector("#live-action-list");
            list.insertAdjacentHTML("beforeend", `
                <li>
                    <span class="action-order">${escapeHtml(data.order)}</span>
                    <div class="action-copy">
                        <strong>${escapeHtml(data.action)}</strong>
                        <span class="${data.urgency === "IMMEDIATE" ? "urgency-immediate" : ""}">紧急程度：${escapeHtml(urgencyLabel(data.urgency))}</span>
                    </div>
                    <span class="action-owner">${escapeHtml(data.owner)}</span>
                </li>`);
            section.hidden = false;
        } else if (type === "ai-review") {
            const section = document.querySelector("#live-ai-review");
            section.innerHTML = `
                <h3>AI 证据复核</h3>
                <div class="data-card">
                    <header><span>${escapeHtml(data.provider)} / ${escapeHtml(data.model)}</span><span>${data.fallbackUsed ? "备用模型" : "主模型"}</span></header>
                    <strong>${escapeHtml(data.content)}</strong>
                </div>`;
            section.hidden = false;
        }
    }

    /**
     * 解析 POST SSE 响应。
     * 浏览器原生 EventSource 只能发送 GET，无法携带告警 JSON，因此这里使用 fetch 的 ReadableStream，
     * 按空行切分 SSE 帧，再把 event 和多行 data 交给上层处理。
     */
    async function consumeEventStream(response, onEvent) {
        if (!response.body) {
            throw new Error("当前浏览器不支持流式响应读取");
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";

        while (true) {
            const {value, done} = await reader.read();
            buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
            buffer = buffer.replace(/\r\n/g, "\n");

            let boundary;
            while ((boundary = buffer.indexOf("\n\n")) >= 0) {
                const frame = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);
                dispatchSseFrame(frame, onEvent);
            }

            if (done) {
                if (buffer.trim()) {
                    dispatchSseFrame(buffer, onEvent);
                }
                break;
            }
        }
    }

    /**
     * 将一帧 SSE 文本转换成统一事件信封，并检查协议 id 与 Run seq。
     * 重复事件直接忽略；发现跳号会记录警告但继续展示，避免一次观测缺口让整份报告不可用。
     */
    function dispatchSseFrame(frame, onEvent) {
        let type = "message";
        let protocolId = null;
        const dataLines = [];
        frame.split("\n").forEach(line => {
            if (line.startsWith("id:")) {
                protocolId = line.slice(3).trim();
            } else if (line.startsWith("event:")) {
                type = line.slice(6).trim();
            } else if (line.startsWith("data:")) {
                dataLines.push(line.slice(5).trimStart());
            }
        });

        if (!dataLines.length) {
            return;
        }
        const rawData = dataLines.join("\n");
        try {
            const envelope = JSON.parse(rawData);
            validateSseEnvelope(protocolId, type, envelope);

            // id 或 seq 小于期望值表示重复投递；当前页面已经处理过，不再次渲染。
            const expectedRunSequence = state.nextRunSequences.get(envelope.runId) ?? 0;
            if (envelope.id <= state.lastSseId || envelope.seq < expectedRunSequence) {
                return;
            }
            if (envelope.id > state.lastSseId + 1 || envelope.seq > expectedRunSequence) {
                console.warn("SSE 事件出现跳号", {
                    expectedId: state.lastSseId + 1,
                    actualId: envelope.id,
                    expectedSeq: expectedRunSequence,
                    actualSeq: envelope.seq,
                    runId: envelope.runId
                });
            }

            state.lastSseId = envelope.id;
            state.nextRunSequences.set(envelope.runId, envelope.seq + 1);
            onEvent(type, envelope.data, envelope);
        } catch (error) {
            throw new Error("服务端返回了无法解析的流式数据", {cause: error});
        }
    }

    /** 协议层 id/event 与 JSON 信封必须完全一致，否则说明代理或服务端产生了损坏事件。 */
    function validateSseEnvelope(protocolId, eventType, envelope) {
        if (!envelope || !Number.isInteger(envelope.id) || envelope.id < 1
            || !Number.isInteger(envelope.seq) || envelope.seq < 0
            || !envelope.runId || !envelope.type || envelope.data == null) {
            throw new Error("SSE 事件信封字段不完整");
        }
        if (protocolId !== String(envelope.id)) {
            throw new Error("SSE 协议 id 与事件信封 id 不一致");
        }
        if (eventType !== envelope.type) {
            throw new Error("SSE event 与事件信封 type 不一致");
        }
    }

    /** 提交告警时携带已有 conversationId，从而让服务端自动载入本会话 Chat Memory。 */
    async function analyzeAlert(alertText) {
        const payload = {alertText};
        if (state.conversationId) {
            payload.conversationId = state.conversationId;
        }

        const response = await fetch("/api/v1/alerts/analyze/stream", {
            method: "POST",
            headers: {"Content-Type": "application/json", "Accept": "text/event-stream"},
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            const errorBody = await response.json().catch(() => ({}));
            const fieldMessage = errorBody.fieldErrors && Object.values(errorBody.fieldErrors)[0];
            throw new Error(fieldMessage || errorBody.message || `请求失败（HTTP ${response.status}）`);
        }

        let receivedReport = false;
        await consumeEventStream(response, (type, data) => {
            if (type === "progress") {
                updateProgress(data);
            } else if (type === "ai-token") {
                updateAiStream(data);
            } else if (["recognition", "evidence", "root-cause", "action", "ai-review"].includes(type)) {
                updateReportSection(type, data);
            } else if (type === "report") {
                receivedReport = true;
                receiveReport(data);
            } else if (type === "error") {
                throw new Error(data.message || "流式分析失败");
            }
        });

        if (!receivedReport) {
            throw new Error("分析连接已结束，但没有收到最终报告");
        }
    }

    /** 保存服务端会话编号并显示报告；后续提交会自动复用该编号。 */
    function receiveReport(report) {
        state.latestReport = report;
        state.conversationId = report.conversationId;
        elements.conversationId.textContent = report.conversationId;
        elements.conversationCard.hidden = false;
        elements.newConversation.hidden = false;
        elements.progressView.hidden = true;
        elements.emptyState.hidden = true;
        elements.downloadReport.hidden = false;
        elements.reportView.innerHTML = renderReport(report);
        elements.reportView.hidden = false;
    }

    /** 任何值进入 HTML 模板前都走此函数，防止用户告警或模型文本注入标签。 */
    function escapeHtml(value) {
        return String(value ?? "-")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    function safeList(value) {
        return Array.isArray(value) ? value : [];
    }

    function riskLabel(risk) {
        return {HIGH: "高风险", MEDIUM: "中风险", LOW: "低风险"}[risk] || risk || "未知风险";
    }

    function urgencyLabel(urgency) {
        return {IMMEDIATE: "立即", HIGH: "高", MEDIUM: "中", LOW: "低"}[urgency] || urgency || "-";
    }

    /**
     * 报告渲染只展示一线运维人员做判断所需的信息：结论、指标、证据、根因、动作和观察项。
     * 原始工具 data 仍保留在 JSON API 中，页面避免直接倾倒大段对象影响阅读。
     */
    function renderReport(report) {
        const recognition = report.recognition || {};
        const rootCause = report.rootCause || {};
        const risk = String(rootCause.finalRisk || "UNKNOWN").toLowerCase();
        const modelSource = report.fallbackUsed ? "备用模型" : "主模型 / 规则兜底";

        const metrics = safeList(recognition.abnormalMetrics).map(metric => `
            <div class="data-card">
                <header><span>${escapeHtml(metric.metricName)}</span><span>${escapeHtml(metric.trend)}</span></header>
                <strong>${escapeHtml(metric.currentValue)} ${escapeHtml(metric.unit || "")}</strong>
                <p>阈值：${metric.threshold == null ? "未提供" : `${escapeHtml(metric.threshold)} ${escapeHtml(metric.unit || "")}`}</p>
            </div>`).join("") || '<p class="helper-text">原始告警中没有可直接提取的数值指标。</p>';

        const evidence = safeList(report.evidence).map(item => `
            <div class="data-card">
                <header><span>${escapeHtml(item.toolName)}</span><span>${escapeHtml(item.status)} · ${escapeHtml(item.durationMs)} ms</span></header>
                <strong>${escapeHtml(item.summary)}</strong>
                <p>证据编号：${escapeHtml(item.evidenceId)}</p>
            </div>`).join("");

        const causes = safeList(rootCause.candidates).map(candidate => `
            <div class="data-card">
                <header><span>候选根因</span><span class="confidence">${Math.round(Number(candidate.confidence || 0) * 100)}%</span></header>
                <strong>${escapeHtml(candidate.description)}</strong>
                <p>证据：${safeList(candidate.evidenceIds).map(escapeHtml).join("、") || "无"}</p>
            </div>`).join("");

        const actions = safeList(report.recommendedActions).map(action => `
            <li>
                <span class="action-order">${escapeHtml(action.order)}</span>
                <div class="action-copy">
                    <strong>${escapeHtml(action.action)}</strong>
                    <span class="${action.urgency === "IMMEDIATE" ? "urgency-immediate" : ""}">紧急程度：${escapeHtml(urgencyLabel(action.urgency))}</span>
                </div>
                <span class="action-owner">${escapeHtml(action.owner)}</span>
            </li>`).join("");

        const observations = safeList(report.followUpMetrics)
            .map(metric => `<li>${escapeHtml(metric)}</li>`).join("");

        return `
            <section class="report-summary">
                <div>
                    <p class="eyebrow">INCIDENT ASSESSMENT</p>
                    <h3>${escapeHtml(recognition.serviceName)} · ${escapeHtml(recognition.alertType)}</h3>
                    <p>${escapeHtml(recognition.summary)}</p>
                </div>
                <span class="risk-badge risk-${escapeHtml(risk)}">${escapeHtml(riskLabel(rootCause.finalRisk))}</span>
            </section>
            <div class="report-meta">
                <span>分析编号 <code>${escapeHtml(report.analysisId)}</code></span>
                <span>模型 ${escapeHtml(report.modelProvider)} / ${escapeHtml(report.modelName)}</span>
                <span>${escapeHtml(modelSource)}</span>
                <span>${escapeHtml(report.generatedAt)}</span>
            </div>
            <section class="report-section"><h3>异常指标</h3><div class="metric-grid">${metrics}</div></section>
            <section class="report-section"><h3>运维工具证据</h3><div class="evidence-grid">${evidence}</div></section>
            <section class="report-section"><h3>根因候选</h3><div class="cause-grid">${causes}</div></section>
            <section class="report-section"><h3>建议处置动作</h3><ol class="action-list">${actions}</ol></section>
            <section class="report-section"><h3>后续观察指标</h3><ul class="observation-list">${observations}</ul></section>`;
    }

    /**
     * 下载按钮调用后端 Markdown 生成接口，保证下载内容与作业要求中的服务端报告一致。
     * 该接口会基于最新告警重新形成一次可审计分析，因此下载期间按钮会暂时锁定。
     */
    async function downloadMarkdown() {
        if (!state.latestAlert || !state.conversationId) {
            return;
        }
        elements.downloadReport.disabled = true;
        elements.downloadReport.textContent = "正在生成…";
        clearError();
        try {
            const response = await fetch("/api/v1/alerts/analyze/markdown", {
                method: "POST",
                headers: {"Content-Type": "application/json", "Accept": "text/markdown"},
                body: JSON.stringify({
                    alertText: state.latestAlert,
                    conversationId: state.conversationId
                })
            });
            if (!response.ok) {
                throw new Error(`报告生成失败（HTTP ${response.status}）`);
            }
            const blobUrl = URL.createObjectURL(await response.blob());
            const link = document.createElement("a");
            link.href = blobUrl;
            link.download = `incident-report-${state.latestReport.analysisId}.md`;
            document.body.appendChild(link);
            link.click();
            link.remove();
            URL.revokeObjectURL(blobUrl);
        } catch (error) {
            showError(error.message);
        } finally {
            elements.downloadReport.disabled = false;
            elements.downloadReport.textContent = "下载 Markdown";
        }
    }

    /** 新建会话只重置浏览器侧编号；服务端旧历史会等待应用重启或未来持久化策略清理。 */
    function resetConversation() {
        state.conversationId = null;
        state.latestAlert = "";
        state.latestReport = null;
        elements.form.reset();
        updateCharacterCount();
        clearError();
        elements.conversationCard.hidden = true;
        elements.newConversation.hidden = true;
        elements.downloadReport.hidden = true;
        elements.progressView.hidden = true;
        elements.reportView.hidden = true;
        elements.reportView.innerHTML = "";
        elements.emptyState.hidden = false;
        setAnalyzing(false);
        elements.alertText.focus();
    }

    elements.form.addEventListener("submit", async event => {
        event.preventDefault();
        if (state.analyzing) {
            return;
        }
        const alertText = elements.alertText.value.trim();
        if (!alertText) {
            showError("请先输入自然语言告警内容。 ");
            elements.alertText.focus();
            return;
        }

        state.latestAlert = alertText;
        showProgress();
        setAnalyzing(true);
        try {
            await analyzeAlert(alertText);
        } catch (error) {
            showError(error.message);
        } finally {
            setAnalyzing(false);
        }
    });

    elements.alertText.addEventListener("input", updateCharacterCount);
    elements.alertText.addEventListener("keydown", event => {
        if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
            event.preventDefault();
            elements.form.requestSubmit();
        }
    });
    document.querySelectorAll("[data-alert]").forEach(button => {
        button.addEventListener("click", () => {
            elements.alertText.value = button.dataset.alert;
            updateCharacterCount();
            elements.alertText.focus();
        });
    });
    elements.newConversation.addEventListener("click", resetConversation);
    elements.downloadReport.addEventListener("click", downloadMarkdown);

    initializeStageList();
    updateCharacterCount();

    /**
     * file:// 没有 Spring Boot 进程，也没有 /api/v1/alerts 接口。
     * 相对资源路径仍允许学习者直接预览样式，但主动锁定提交能给出比网络错误更准确的反馈。
     */
    if (window.location.protocol === "file:") {
        elements.runtimeWarning.hidden = false;
        elements.submitButton.disabled = true;
        elements.buttonLabel.textContent = "请先启动 Spring Boot";
    }
})();
