//! 桌面端 JSON Lines 协议，只暴露模型切换和文本聊天。

use serde::Deserialize;
use serde_json::{Value, json};

use crate::{
    NautilusAgent,
    config::{GeminiMode, LLMConfig, ModelProvider},
    credentials::CredentialStore,
};

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum Request {
    SelectModel {
        model_id: String,
    },
    Chat {
        message: String,
    },
    GetCredentials,
    SaveCredentials {
        deepseek: Option<String>,
        openai: Option<String>,
        gemini: Option<String>,
        plaintext_acknowledged: bool,
    },
    DeleteCredentials {
        provider: String,
    },
    Reset,
    Shutdown,
}

#[derive(Clone, Copy)]
enum ModelChoice {
    DeepSeek,
    OpenAI,
    Flash,
    Argon,
    Auto,
}

impl ModelChoice {
    fn parse(id: &str) -> Result<Self, String> {
        match id {
            "deepseek" => Ok(Self::DeepSeek),
            "openai" => Ok(Self::OpenAI),
            "gemini-flash" => Ok(Self::Flash),
            "gemini-argon" => Ok(Self::Argon),
            "gemini-auto" => Ok(Self::Auto),
            _ => Err(format!("未知模型选项: {id}")),
        }
    }

    fn settings(self, store: &CredentialStore) -> Result<(LLMConfig, Option<GeminiMode>), String> {
        let (provider, mode) = match self {
            Self::DeepSeek => (ModelProvider::DeepSeek, None),
            Self::OpenAI => (ModelProvider::OpenAI, None),
            Self::Flash => (ModelProvider::Gemini, Some(GeminiMode::FlashSearch)),
            Self::Argon => (ModelProvider::Gemini, Some(GeminiMode::Argon)),
            Self::Auto => (ModelProvider::Gemini, Some(GeminiMode::Auto)),
        };
        Ok((
            LLMConfig::for_user_key(provider, store.key(provider)?, mode)?,
            mode,
        ))
    }

    fn create(self, store: &CredentialStore) -> Result<NautilusAgent, String> {
        let (settings, mode) = self.settings(store)?;
        NautilusAgent::with_user_config(settings, mode)
    }

    fn switch(self, agent: &mut NautilusAgent, store: &CredentialStore) -> Result<(), String> {
        let (settings, mode) = self.settings(store)?;
        agent.switch_user_config(settings, mode)
    }
}

pub struct ChatSession {
    credentials: CredentialStore,
    agent: Option<NautilusAgent>,
    model_id: Option<String>,
}

impl ChatSession {
    pub fn new(credentials: CredentialStore) -> Self {
        Self {
            credentials,
            agent: None,
            model_id: None,
        }
    }

    pub fn ready(&self) -> Value {
        let models = [
            (
                "deepseek",
                "DeepSeek",
                "通用对话",
                "DeepSeek",
                ModelProvider::DeepSeek,
            ),
            (
                "openai",
                "OpenAI",
                "通用对话",
                "OpenAI",
                ModelProvider::OpenAI,
            ),
            (
                "gemini-flash",
                "Gemini 3.8 Flash",
                "Extended Thinking · Search",
                "Gemini",
                ModelProvider::Gemini,
            ),
            (
                "gemini-argon",
                "Gemini 4 Argon",
                "深度思考 · Search",
                "Gemini",
                ModelProvider::Gemini,
            ),
            (
                "gemini-auto",
                "Gemini Auto",
                "根据任务选择 Flash / Argon",
                "Gemini",
                ModelProvider::Gemini,
            ),
        ]
        .into_iter()
        .map(|(id, name, subtitle, provider, key)| {
            json!({
                "id": id, "name": name, "subtitle": subtitle, "provider": provider,
                "configured": self.credentials.key(key).is_ok()
            })
        })
        .collect::<Vec<_>>();
        json!({"type": "ready", "protocol": 2, "models": models, "credentials": self.credentials.status()})
    }

    pub async fn handle(&mut self, request: Request) -> Value {
        match self.execute(request).await {
            Ok(response) => response,
            Err(message) => json!({"type": "error", "message": self.credentials.redact(&message)}),
        }
    }

    async fn execute(&mut self, request: Request) -> Result<Value, String> {
        match request {
            Request::SelectModel { model_id } => {
                let choice = ModelChoice::parse(&model_id)?;
                if let Some(agent) = self.agent.as_mut() {
                    choice.switch(agent, &self.credentials)?;
                } else {
                    self.agent = Some(choice.create(&self.credentials)?);
                }
                self.model_id = Some(model_id);
                let agent = self.agent.as_ref().ok_or("模型尚未初始化")?;
                Ok(
                    json!({"type": "selected", "model_id": self.model_id, "model": agent.model(),
                    "provider": agent.provider().name(), "web_search": agent.web_search_enabled()}),
                )
            }
            Request::Chat { message } => {
                if message.trim().is_empty() {
                    return Err("请输入聊天内容".into());
                }
                let agent = self.agent.as_mut().ok_or("请先选择一个模型")?;
                let text = agent.chat_text(&message).await?;
                Ok(
                    json!({"type": "reply", "text": text, "model": agent.model(),
                    "provider": agent.provider().name(), "model_id": self.model_id}),
                )
            }
            Request::GetCredentials => {
                Ok(json!({"type": "credentials", "credentials": self.credentials.status()}))
            }
            Request::SaveCredentials {
                deepseek,
                openai,
                gemini,
                plaintext_acknowledged,
            } => {
                self.credentials
                    .save([deepseek, openai, gemini], plaintext_acknowledged)?;
                self.agent = None;
                self.model_id = None;
                Ok(json!({"type": "credentials", "credentials": self.credentials.status()}))
            }
            Request::DeleteCredentials { provider } => {
                self.credentials.delete(&provider)?;
                self.agent = None;
                self.model_id = None;
                Ok(json!({"type": "credentials", "credentials": self.credentials.status()}))
            }
            Request::Reset => {
                let replacement = self
                    .model_id
                    .as_deref()
                    .map(ModelChoice::parse)
                    .transpose()?
                    .map(|choice| choice.create(&self.credentials))
                    .transpose()?;
                self.agent = replacement;
                Ok(json!({"type": "reset"}))
            }
            Request::Shutdown => Ok(json!({"type": "bye"})),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn session() -> ChatSession {
        let directory =
            std::env::temp_dir().join(format!("nautilus-empty-profile-{}", std::process::id()));
        ChatSession::new(CredentialStore::open(directory).unwrap())
    }

    #[tokio::test]
    async fn invalid_requests_do_not_end_the_session() {
        let mut session = session();
        assert_eq!(
            session
                .handle(Request::Chat {
                    message: "hello".into()
                })
                .await["type"],
            "error"
        );
        assert_eq!(
            session
                .handle(Request::SelectModel {
                    model_id: "unknown".into()
                })
                .await["type"],
            "error"
        );
        assert_eq!(session.handle(Request::Reset).await["type"], "reset");
        assert_eq!(session.handle(Request::Shutdown).await["type"], "bye");
    }

    #[test]
    fn catalog_has_all_five_models_and_protocol_rejects_tool_commands() {
        assert_eq!(session().ready()["models"].as_array().unwrap().len(), 5);
        assert!(
            serde_json::from_str::<Request>(r#"{"type":"execute_tool","command":"test"}"#).is_err()
        );
        assert!(
            serde_json::from_str::<Request>(r#"{"type":"chat","message":"hi","tool":"test"}"#)
                .is_err()
        );
        assert!(
            serde_json::from_str::<Request>(r#"{"type":"chat","message":"你好\\n多行"}"#).is_ok()
        );
    }
}
