//! Unified model client with OpenAI-compatible, Gemini and Claude native transports.

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

mod claude;
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
    #[cfg(test)]
    mock: Option<(std::sync::Arc<std::sync::Mutex<Vec<Value>>>, bool)>,
    #[cfg(test)]
    mock_reply: Option<String>,
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
            #[cfg(test)]
            mock: None,
            #[cfg(test)]
            mock_reply: None,
        })
    }

    #[cfg(test)]
    pub(crate) fn test_transport(
        mut self,
        requests: std::sync::Arc<std::sync::Mutex<Vec<Value>>>,
        fail: bool,
    ) -> Self {
        self.mock = Some((requests, fail));
        self
    }

    #[cfg(test)]
    pub(crate) fn test_reply(mut self, text: &str) -> Self {
        self.mock_reply = Some(text.into());
        self
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
        if self.provider == ModelProvider::Claude {
            let (text, complete) = self
                .send_collaborative(self.build_request(history)?)
                .await?;
            return Ok(LLMResponse::Text(if complete {
                text
            } else {
                format!("{text}\n\n⚠️ Claude 输出未完整结束，请继续生成或缩小任务范围。")
            }));
        }
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

    /// Budgeted text-only request: no search/tool fees and no automatic retries.
    pub async fn chat_limited(
        &self,
        history: &[Message],
        limit: u32,
    ) -> Result<(String, bool), String> {
        let body = self.build_limited_request(history, limit)?;
        self.send_collaborative(body).await
    }

    pub(crate) async fn chat_collaborative(
        &self,
        history: &[Message],
    ) -> Result<(String, bool), String> {
        self.send_collaborative(self.build_request(history)?).await
    }

    async fn send_collaborative(&self, body: Value) -> Result<(String, bool), String> {
        #[cfg(test)]
        if let Some((requests, fail)) = &self.mock {
            requests.lock().unwrap().push(body);
            return if *fail {
                Err("模拟服务不可用".into())
            } else {
                Ok((
                    self.mock_reply
                        .clone()
                        .unwrap_or_else(|| format!("result from {}", self.model)),
                    true,
                ))
            };
        }
        let response = self
            .collaborative_request()
            .json(&body)
            .send()
            .await
            .and_then(reqwest::Response::error_for_status)
            .map_err(|error| {
                format!(
                    "{} 请求失败（网络、权限、限流或模型不可用；HTTP {}）",
                    self.provider.name(),
                    error
                        .status()
                        .map(|s| s.as_u16().to_string())
                        .unwrap_or_else(|| "未知".into())
                )
            })?;
        let value: Value = response.json().await.map_err(|_| "模型响应无效")?;
        if self.provider == ModelProvider::Claude {
            return Self::parse_claude_response(&value);
        }
        let choice = &value["choices"][0];
        let text = choice["message"]["content"]
            .as_str()
            .filter(|s| !s.trim().is_empty())
            .ok_or("模型未返回可见文本")?;
        Ok((text.to_owned(), choice["finish_reason"] == "stop"))
    }

    fn build_limited_request(&self, history: &[Message], limit: u32) -> Result<Value, String> {
        if limit < 200 {
            return Err("输出预算不足 200 tokens".into());
        }
        let mut body = self.build_request(history)?;
        body["max_tokens"] = limit.into();
        if self.provider == ModelProvider::OpenAI {
            body.as_object_mut().unwrap().remove("max_tokens");
            body["max_completion_tokens"] = limit.into();
        }
        Ok(body)
    }

    fn build_request(&self, history: &[Message]) -> Result<Value, String> {
        if self.provider == ModelProvider::Claude {
            return self.build_claude_request(history);
        }
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

    fn collaborative_request(&self) -> reqwest::RequestBuilder {
        if self.provider == ModelProvider::Claude {
            self.client
                .post(self.config.url("/messages"))
                .header("x-api-key", self.api_key_header.clone())
                .header("anthropic-version", "2023-06-01")
        } else {
            self.client
                .post(self.config.url("/chat/completions"))
                .headers(self.config.headers())
        }
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
