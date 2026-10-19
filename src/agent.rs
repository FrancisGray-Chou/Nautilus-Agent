//! Agent 的执行循环与会话状态。

use std::collections::HashMap;

use crate::{
    config::{GeminiMode, LLMConfig, ModelProvider},
    llm::{LLMClient, LLMResponse},
    logbook::Logbook,
    message::Message,
    tool::Tool,
};

pub struct NautilusAgent {
    engine: LLMClient,
    logbook: Logbook,
    tools: HashMap<String, Box<dyn Tool>>,
}

impl NautilusAgent {
    pub(crate) fn with_user_config(
        settings: LLMConfig,
        mode: Option<GeminiMode>,
    ) -> Result<Self, String> {
        Ok(Self {
            engine: LLMClient::with_user_config(settings, mode)?,
            logbook: Logbook::new(),
            tools: HashMap::new(),
        })
    }

    pub(crate) fn switch_user_config(
        &mut self,
        settings: LLMConfig,
        mode: Option<GeminiMode>,
    ) -> Result<(), String> {
        self.engine = LLMClient::with_user_config(settings, mode)?;
        Ok(())
    }
    pub fn new() -> Result<Self, String> {
        Self::with_provider(ModelProvider::DeepSeek)
    }

    pub fn with_provider(provider: ModelProvider) -> Result<Self, String> {
        Ok(Self {
            engine: LLMClient::with_provider(provider)?,
            logbook: Logbook::new(),
            tools: HashMap::new(),
        })
    }

    pub fn with_gemini_mode(mode: GeminiMode) -> Result<Self, String> {
        Ok(Self {
            engine: LLMClient::with_gemini_mode(mode)?,
            logbook: Logbook::new(),
            tools: HashMap::new(),
        })
    }

    pub fn switch_gemini_mode(&mut self, mode: GeminiMode) -> Result<(), String> {
        self.engine = LLMClient::with_gemini_mode(mode)?;
        Ok(())
    }

    /// 切换模型并保留历史；配置无效时保持原客户端。
    pub fn switch_provider(&mut self, provider: ModelProvider) -> Result<(), String> {
        self.engine = LLMClient::with_provider(provider)?;
        Ok(())
    }

    pub fn provider(&self) -> ModelProvider {
        self.engine.provider()
    }

    pub fn model(&self) -> &str {
        self.engine.model()
    }

    pub fn web_search_enabled(&self) -> bool {
        self.engine.web_search_enabled()
    }

    pub(crate) fn set_web_search(&mut self, enabled: bool) {
        self.engine.set_web_search(enabled);
    }

    pub fn gemini_mode(&self) -> Option<GeminiMode> {
        self.engine.gemini_mode()
    }

    pub fn model_for_task(&self, input: &str) -> &str {
        self.engine.model_for_task(input)
    }

    #[cfg(test)]
    pub(crate) fn history(&self) -> &[Message] {
        self.logbook.get_history()
    }

    pub(crate) fn restore_history(&mut self, history: Vec<Message>) {
        self.logbook = Logbook::new();
        for message in history {
            self.logbook.push(message);
        }
    }

    /// 纯文本聊天：仅在成功后写入本轮历史，不执行自定义工具。
    pub async fn chat_text(&mut self, input: &str) -> Result<String, String> {
        self.engine.prepare_task(input);
        let mut history = self.logbook.get_history().to_vec();
        history.push(Message::user(input));
        match self.engine.chat(&history, &HashMap::new()).await? {
            LLMResponse::Text(text) => {
                self.logbook.push(Message::user(input));
                self.logbook.push(Message::assistant(&text));
                Ok(text)
            }
            LLMResponse::ToolCall { .. } => Err("文本聊天模式不执行自定义工具".into()),
        }
    }

    pub async fn run(&mut self, user_input: &str) -> Result<String, String> {
        self.engine.prepare_task(user_input);
        // 1. 记录用户输入
        self.logbook.push(Message::user(user_input));

        loop {
            // 2. 核心循环：请求大模型
            let response = self
                .engine
                .chat(self.logbook.get_history(), &self.tools)
                .await?;

            // 3. 处理模型响应
            match response {
                LLMResponse::ToolCall {
                    tool_name,
                    arguments,
                } => {
                    if let Some(tool) = self.tools.get(&tool_name) {
                        println!(">> Agent 决定调用工具: {}", tool_name);
                        let result = tool.execute(arguments).await?;
                        self.logbook.push(Message::tool_result(tool_name, result));
                        // 继续 loop 循环，把工具结果发回给大模型
                    } else {
                        return Err(format!("Tool not found: {tool_name}"));
                    }
                }
                LLMResponse::Text(text) => {
                    self.logbook.push(Message::assistant(&text));
                    return Ok(text); // 获取最终回答，跳出循环
                }
            }
        }
    }
}
