//! DeepSeek、OpenAI 与 Gemini 的统一 Chat Completions 适配层。

use std::collections::HashMap;

use async_openai::{
    config::{Config, OpenAIConfig},
    types::{
        ChatCompletionRequestAssistantMessageArgs, ChatCompletionRequestMessage,
        ChatCompletionRequestUserMessageArgs, CreateChatCompletionRequestArgs,
        CreateChatCompletionResponse,
    },
};
use serde_json::Value;

use crate::{
    config::{GeminiMode, LLMConfig, ModelProvider},
    message::Message,
    tool::Tool,
};

mod gemini;

#[derive(Debug, PartialEq)]
pub enum LLMResponse {
    ToolCall { tool_name: String, arguments: Value },
    Text(String),
}

// 复用 SDK 的消息类型，通过 HTTP 发送包含 Gemini 思考参数的请求。
pub struct LLMClient {
    client: reqwest::Client,
    config: OpenAIConfig,
    provider: ModelProvider,
    model: String,
    web_search: bool,
    api_key_header: reqwest::header::HeaderValue,
    gemini_mode: Option<GeminiMode>,
}

impl LLMClient {
    pub(crate) fn with_user_config(
        settings: LLMConfig,
        mode: Option<GeminiMode>,
    ) -> Result<Self, String> {
        let mut client = Self::from_config(settings)?;
        client.gemini_mode = mode;
        Ok(client)
    }
    pub fn new() -> Result<Self, String> {
        Self::with_provider(ModelProvider::DeepSeek)
    }

    pub fn with_provider(provider: ModelProvider) -> Result<Self, String> {
        Self::from_config(LLMConfig::from_env(provider)?)
    }

    pub fn with_gemini_mode(mode: GeminiMode) -> Result<Self, String> {
        let mut client = Self::from_config(LLMConfig::for_gemini_mode(mode)?)?;
        client.gemini_mode = Some(mode);
        Ok(client)
    }

    fn from_config(settings: LLMConfig) -> Result<Self, String> {
        let mut api_key_header = reqwest::header::HeaderValue::from_str(&settings.api_key)
            .map_err(|_| "API 密钥包含无效的 HTTP 请求头字符".to_string())?;
        api_key_header.set_sensitive(true);
        let config = OpenAIConfig::new()
            .with_api_key(settings.api_key)
            .with_api_base(settings.provider.api_base());
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(180))
            .build()
            .map_err(|e| format!("创建 HTTP 客户端失败: {e}"))?;
        Ok(Self {
            client,
            config,
            provider: settings.provider,
            model: settings.model,
            web_search: settings.web_search,
            api_key_header,
            gemini_mode: None,
        })
    }

    pub fn provider(&self) -> ModelProvider {
        self.provider
    }

    pub fn model(&self) -> &str {
        &self.model
    }

    pub fn web_search_enabled(&self) -> bool {
        self.provider == ModelProvider::Gemini && self.web_search
    }

    pub fn gemini_mode(&self) -> Option<GeminiMode> {
        self.gemini_mode
    }

    pub fn model_for_task(&self, input: &str) -> &str {
        match self.gemini_mode {
            Some(mode) => mode.model_for_task(input, &self.model),
            None => &self.model,
        }
    }

    pub(crate) fn prepare_task(&mut self, input: &str) {
        self.model = self.model_for_task(input).to_string();
    }

    pub async fn chat(
        &self,
        history: &[Message],
        _tools: &HashMap<String, Box<dyn Tool>>,
    ) -> Result<LLMResponse, String> {
        if self.web_search_enabled() {
            return self.chat_with_google_search(history).await;
        }
        let request = self.build_request(history)?;
        let response = self
            .client
            .post(self.config.url("/chat/completions"))
            .headers(self.config.headers())
            .json(&request)
            .send()
            .await
            .and_then(reqwest::Response::error_for_status)
            .map_err(|e| format!("{} API 请求失败: {e}", self.provider.name()))?;
        let response = response
            .json::<CreateChatCompletionResponse>()
            .await
            .map_err(|e| format!("解析 {} 响应失败: {e}", self.provider.name()))?;
        Self::parse_response(response).map_err(|e| format!("{}: {e}", self.provider.name()))
    }

    fn build_request(&self, history: &[Message]) -> Result<Value, String> {
        // 将内部消息转换为各服务兼容的消息格式。
        let mut openai_messages: Vec<ChatCompletionRequestMessage> = Vec::new();

        for msg in history {
            match msg {
                Message::User(content) => {
                    let req_msg = ChatCompletionRequestUserMessageArgs::default()
                        .content(content.as_str())
                        .build()
                        .map_err(|e| format!("构建消息失败: {e}"))?;
                    openai_messages.push(req_msg.into());
                }
                Message::Assistant(content) => {
                    let req_msg = ChatCompletionRequestAssistantMessageArgs::default()
                        .content(content.as_str())
                        .build()
                        .map_err(|e| format!("构建消息失败: {e}"))?;
                    openai_messages.push(req_msg.into());
                }
                Message::ToolResult(_, _) => {
                    // 暂略：等真正实现工具调用时，再把 Tool 结果映射给 DeepSeek
                }
            }
        }

        // 2. 构造请求参数
        let request = CreateChatCompletionRequestArgs::default()
            .model(&self.model)
            .messages(openai_messages)
            .build()
            .map_err(|e| format!("构建请求失败: {e}"))?;
        let mut body = serde_json::to_value(request).map_err(|e| format!("序列化请求失败: {e}"))?;
        if self.provider == ModelProvider::Gemini {
            body["reasoning_effort"] = Value::String("high".into());
        }
        Ok(body)
    }

    fn parse_response(response: CreateChatCompletionResponse) -> Result<LLMResponse, String> {
        response
            .choices
            .into_iter()
            .next()
            .and_then(|choice| choice.message.content)
            .filter(|content| !content.trim().is_empty())
            .map(LLMResponse::Text)
            .ok_or_else(|| "模型返回了空消息".to_string())
    }
}

#[cfg(test)]
mod tests;
