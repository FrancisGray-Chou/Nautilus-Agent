//! Gemini 原生搜索接入；保留 Google 返回的来源信息。

use std::collections::HashSet;

use serde_json::{Value, json};

use super::{LLMClient, LLMResponse};
use crate::message::Message;

impl LLMClient {
    fn gemini_search_url(&self) -> Result<reqwest::Url, String> {
        let mut url =
            reqwest::Url::parse("https://generativelanguage.googleapis.com/v1beta/models/")
                .map_err(|e| format!("构建 Gemini 地址失败: {e}"))?;
        url.path_segments_mut()
            .map_err(|_| "无效的 Gemini API 地址".to_string())?
            .pop_if_empty()
            .push(&format!("{}:generateContent", self.model));
        Ok(url)
    }

    fn build_gemini_search_request(&self, history: &[Message]) -> Result<Value, String> {
        let mut contents = Vec::new();
        for message in history {
            let (role, text) = match message {
                Message::User(text) => ("user", text),
                Message::Assistant(text) => ("model", text),
                Message::ToolResult(_, _) => {
                    return Err("Gemini 搜索模式尚未接入自定义工具结果".into());
                }
            };
            contents.push(json!({"role": role, "parts": [{"text": text}]}));
        }
        let thinking = if self.model.starts_with("gemini-2.5-") {
            json!({"thinkingBudget": 24576})
        } else {
            json!({"thinkingLevel": "high"})
        };
        Ok(json!({
            "contents": contents,
            "tools": [{"google_search": {}}],
            "generationConfig": {"thinkingConfig": thinking}
        }))
    }

    pub(super) async fn chat_with_google_search(
        &self,
        history: &[Message],
    ) -> Result<LLMResponse, String> {
        let response = self
            .client
            .post(self.gemini_search_url()?)
            .header("x-goog-api-key", self.api_key_header.clone())
            .json(&self.build_gemini_search_request(history)?)
            .send()
            .await
            .map_err(|e| format!("Gemini 搜索请求失败: {e}"))?;
        let status = response.status();
        let body: Value = response
            .json()
            .await
            .map_err(|e| format!("解析 Gemini 搜索响应失败: {e}"))?;
        if !status.is_success() {
            let message = body["error"]["message"]
                .as_str()
                .unwrap_or("未返回错误详情");
            return Err(format!("Gemini 搜索请求失败 ({status}): {message}"));
        }
        Self::parse_gemini_search_response(&body)
    }

    fn parse_gemini_search_response(body: &Value) -> Result<LLMResponse, String> {
        let candidate = body["candidates"]
            .as_array()
            .and_then(|items| items.first())
            .ok_or_else(|| {
                format!(
                    "Gemini 未返回候选回答: {}",
                    body["promptFeedback"]["blockReason"]
                        .as_str()
                        .unwrap_or("未知原因")
                )
            })?;
        let mut text = candidate["content"]["parts"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|part| part["thought"].as_bool() != Some(true))
            .filter_map(|part| part["text"].as_str())
            .collect::<String>();
        if text.trim().is_empty() {
            return Err(format!(
                "Gemini 返回了空消息: {}",
                candidate["finishReason"].as_str().unwrap_or("未知原因")
            ));
        }

        let metadata = &candidate["groundingMetadata"];
        let queries: Vec<&str> = metadata["webSearchQueries"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .filter(|query| !query.trim().is_empty())
            .collect();
        if !queries.is_empty() {
            text.push_str(&format!("\n\n搜索词：{}", queries.join("；")));
        }
        let mut seen = HashSet::new();
        let mut sources = Vec::new();
        for chunk in metadata["groundingChunks"].as_array().into_iter().flatten() {
            let Some(uri) = chunk["web"]["uri"].as_str() else {
                continue;
            };
            if !seen.insert(uri) {
                continue;
            }
            let title = chunk["web"]["title"].as_str().unwrap_or("网页来源");
            sources.push(format!("[{}] {}\n{}", sources.len() + 1, title, uri));
        }
        if sources.is_empty() {
            text.push_str(
                "\n\n联网搜索：本次未返回可核验的网页来源，不能据此确认回答已经联网核实。",
            );
        } else {
            text.push_str("\n\n联网搜索来源：\n");
            text.push_str(&sources.join("\n"));
        }
        Ok(LLMResponse::Text(text))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{
        GEMINI_ARGON_MODEL, GEMINI_FLASH_MODEL, GeminiMode, LLMConfig, ModelProvider,
    };

    fn client(model: &str) -> LLMClient {
        LLMClient::from_config(LLMConfig {
            provider: ModelProvider::Gemini,
            api_key: "test-key".into(),
            model: model.into(),
            web_search: true,
        })
        .unwrap()
    }

    #[test]
    fn auto_changes_the_actual_request_model_between_tasks() {
        let mut client = client(GEMINI_FLASH_MODEL);
        client.gemini_mode = Some(GeminiMode::Auto);
        for (task, expected) in [
            ("搜索今天的新闻", GEMINI_FLASH_MODEL),
            ("证明算法正确性", GEMINI_ARGON_MODEL),
            ("继续", GEMINI_ARGON_MODEL),
            ("你好", GEMINI_FLASH_MODEL),
        ] {
            client.prepare_task(task);
            assert_eq!(client.model(), expected);
            assert!(
                client
                    .gemini_search_url()
                    .unwrap()
                    .as_str()
                    .ends_with(&format!("/{expected}:generateContent"))
            );
            let request = client
                .build_gemini_search_request(&[Message::user(task)])
                .unwrap();
            assert_eq!(request["tools"], json!([{"google_search": {}}]));
            assert_eq!(
                request["generationConfig"]["thinkingConfig"],
                json!({"thinkingLevel": "high"})
            );
        }
    }

    #[test]
    fn native_request_enables_search_thinking_and_preserves_history() {
        let client = client("gemini-3.8-flash");
        assert!(client.web_search_enabled());
        assert_eq!(
            client.gemini_search_url().unwrap().as_str(),
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
        );
        let request = client
            .build_gemini_search_request(&[
                Message::user("第一轮"),
                Message::assistant("回复"),
                Message::user("请搜索今天的消息"),
            ])
            .unwrap();
        assert_eq!(request["tools"], json!([{"google_search": {}}]));
        assert_eq!(
            request["generationConfig"]["thinkingConfig"],
            json!({"thinkingLevel": "high"})
        );
        assert_eq!(
            request["contents"],
            json!([
                {"role": "user", "parts": [{"text": "第一轮"}]},
                {"role": "model", "parts": [{"text": "回复"}]},
                {"role": "user", "parts": [{"text": "请搜索今天的消息"}]}
            ])
        );
        let request = self::client("gemini-2.5-pro")
            .build_gemini_search_request(&[])
            .unwrap();
        assert_eq!(
            request["generationConfig"]["thinkingConfig"],
            json!({"thinkingBudget": 24576})
        );
    }

    #[test]
    fn grounded_reply_shows_sources_without_thoughts_or_duplicate_links() {
        let body = json!({"candidates": [{
            "content": {"parts": [{"thought": true, "text": "internal-thought"}, {"text": "中文答案"}]},
            "groundingMetadata": {
                "webSearchQueries": ["最新消息"],
                "groundingChunks": [
                    {"web": {"uri": "https://example.com/news", "title": "新闻来源"}},
                    {"web": {"uri": "https://example.com/news", "title": "重复"}}
                ]
            }
        }]});
        let LLMResponse::Text(text) = LLMClient::parse_gemini_search_response(&body).unwrap()
        else {
            panic!("expected text");
        };
        assert!(text.contains("中文答案"));
        assert!(text.contains("搜索词：最新消息"));
        assert!(text.contains("新闻来源"));
        assert_eq!(text.matches("https://example.com/news").count(), 1);
        assert!(!text.contains("internal-thought"));
    }

    #[test]
    fn ungrounded_reply_does_not_claim_search_was_used() {
        let body = json!({"candidates": [{"content": {"parts": [{"text": "你好"}]}}]});
        let LLMResponse::Text(text) = LLMClient::parse_gemini_search_response(&body).unwrap()
        else {
            panic!("expected text");
        };
        assert!(text.contains("不能据此确认"));
        assert!(!text.contains("联网搜索来源："));
        assert!(
            LLMClient::parse_gemini_search_response(
                &json!({"promptFeedback": {"blockReason": "SAFETY"}})
            )
            .is_err()
        );
        assert!(
            LLMClient::parse_gemini_search_response(
                &json!({"candidates": [{"finishReason": "SAFETY"}]})
            )
            .is_err()
        );
    }
}
