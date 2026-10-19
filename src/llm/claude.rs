//! Anthropic Messages API; return visible text and preserve completion status.
use super::LLMClient;
use crate::message::Message;
use serde_json::{Value, json};

impl LLMClient {
    pub(super) fn build_claude_request(&self, history: &[Message]) -> Result<Value, String> {
        let messages = history
            .iter()
            .map(|message| match message {
                Message::User(text) => Ok(json!({"role": "user", "content": text})),
                Message::Assistant(text) => Ok(json!({"role": "assistant", "content": text})),
                Message::ToolResult(_, _) => Err("Claude 暂未接入自定义工具结果".to_string()),
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(json!({"model": self.model, "messages": messages, "max_tokens": 16384}))
    }

    pub(super) fn parse_claude_response(body: &Value) -> Result<(String, bool), String> {
        if body["type"] == "error" {
            return Err("Claude 返回 API 错误，请检查密钥、模型访问权限与配额".into());
        }
        let text = body["content"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|block| block["type"] == "text")
            .filter_map(|block| block["text"].as_str())
            .collect::<String>();
        if text.trim().is_empty() {
            return Err("Claude 未返回可见文本，请检查模型输出限制或重试".into());
        }
        Ok((
            text,
            matches!(
                body["stop_reason"].as_str(),
                Some("end_turn" | "stop_sequence")
            ),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{ClaudeModel, LLMConfig, ModelProvider};

    #[test]
    fn all_presets_use_native_endpoint_headers_history_and_limits() {
        for model in ClaudeModel::ALL {
            let client = LLMClient::with_user_config(
                LLMConfig {
                    provider: ModelProvider::Claude,
                    api_key: "offline-key".into(),
                    model: model.api_id().into(),
                    web_search: false,
                },
                None,
            )
            .unwrap();
            let history = [
                Message::user("你好"),
                Message::assistant("Hello"),
                Message::user("继续"),
            ];
            let body = client.build_request(&history).unwrap();
            assert_eq!(body["model"], model.api_id());
            assert_eq!(body["messages"].as_array().unwrap().len(), 3);
            assert_eq!(
                body["messages"][1],
                json!({"role": "assistant", "content": "Hello"})
            );
            assert_eq!(body["max_tokens"], 16384);
            assert!(body.get("reasoning_effort").is_none());
            let request = client.collaborative_request().json(&body).build().unwrap();
            assert_eq!(
                request.url().as_str(),
                "https://api.anthropic.com/v1/messages"
            );
            assert_eq!(request.headers()["x-api-key"], "offline-key");
            assert_eq!(request.headers()["anthropic-version"], "2023-06-01");
            assert!(!request.headers().contains_key("authorization"));
            assert_eq!(
                client.build_limited_request(&history, 237).unwrap()["max_tokens"],
                237
            );
            assert!(
                client
                    .build_request(&[Message::tool_result("tool".into(), "result".into())])
                    .is_err()
            );
        }
    }

    #[test]
    fn visible_blocks_only_and_truncation_is_not_success() {
        for (reason, complete) in [
            ("end_turn", true),
            ("stop_sequence", true),
            ("max_tokens", false),
            ("pause_turn", false),
            ("refusal", false),
            ("tool_use", false),
        ] {
            let result = LLMClient::parse_claude_response(&json!({"content": [
                {"type": "thinking", "thinking": "private"}, {"type": "text", "text": "你好"},
                {"type": "redacted_thinking", "data": "opaque"}, {"type": "text", "text": "世界"}
            ], "stop_reason": reason}))
            .unwrap();
            assert_eq!(result, ("你好世界".into(), complete));
        }
        for body in [
            json!({}),
            json!({"content": []}),
            json!({"content": [{"type": "text", "text": "  "}]}),
            json!({"type": "error", "error": {"message": "secret"}}),
        ] {
            let error = LLMClient::parse_claude_response(&body).unwrap_err();
            assert!(!error.contains("secret"));
        }
    }
}
