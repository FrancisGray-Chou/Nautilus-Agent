//! 桌面端 JSON Lines 协议，只暴露模型切换和文本聊天。

use serde::Deserialize;
use serde_json::{Value, json};

use crate::{
    NautilusAgent,
    config::{ClaudeModel, GeminiMode, LLMConfig, ModelProvider},
    credentials::CredentialStore,
};

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum Request {
    SelectModel {
        model_id: String,
    },
    ConfigureMixture {
        models: Option<Vec<String>>,
        members: Option<Vec<crate::mixture::MemberAssignment>>,
        #[serde(default)]
        allow_role_fallback: bool,
    },
    Chat {
        message: String,
    },
    GetCredentials,
    SaveCredentials {
        deepseek: Option<String>,
        openai: Option<String>,
        gemini: Option<String>,
        claude: Option<String>,
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
    DeepSeekFlash,
    DeepSeekPro,
    OpenAI,
    Claude(ClaudeModel),
    Flash,
    Argon,
    Auto,
}

impl ModelChoice {
    fn parse(id: &str) -> Result<Self, String> {
        match id {
            "deepseek" => Ok(Self::DeepSeek),
            "deepseek-flash" => Ok(Self::DeepSeekFlash),
            "deepseek-pro" => Ok(Self::DeepSeekPro),
            "openai" => Ok(Self::OpenAI),
            "gemini-flash" => Ok(Self::Flash),
            "gemini-argon" => Ok(Self::Argon),
            "gemini-auto" => Ok(Self::Auto),
            _ => ClaudeModel::parse(id)
                .map(Self::Claude)
                .ok_or_else(|| format!("未知模型选项: {id}")),
        }
    }

    fn settings(self, store: &CredentialStore) -> Result<(LLMConfig, Option<GeminiMode>), String> {
        let (provider, mode) = match self {
            Self::DeepSeek | Self::DeepSeekFlash | Self::DeepSeekPro => {
                (ModelProvider::DeepSeek, None)
            }
            Self::OpenAI => (ModelProvider::OpenAI, None),
            Self::Claude(_) => (ModelProvider::Claude, None),
            Self::Flash => (ModelProvider::Gemini, Some(GeminiMode::FlashSearch)),
            Self::Argon => (ModelProvider::Gemini, Some(GeminiMode::Argon)),
            Self::Auto => (ModelProvider::Gemini, Some(GeminiMode::Auto)),
        };
        let mut settings = LLMConfig::for_user_key(provider, store.key(provider)?, mode)?;
        match self {
            Self::Claude(model) => settings.model = model.api_id().into(),
            Self::DeepSeekFlash => settings.model = crate::config::DEFAULT_DEEPSEEK_MODEL.into(),
            Self::DeepSeekPro => settings.model = crate::config::DEEPSEEK_PRO_MODEL.into(),
            _ => {}
        }
        Ok((settings, mode))
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
    mixture: Option<crate::mixture::Mixture>,
}

impl ChatSession {
    pub fn new(credentials: CredentialStore) -> Self {
        Self {
            credentials,
            agent: None,
            model_id: None,
            mixture: None,
        }
    }

    pub fn ready(&self) -> Value {
        let mut models = [
            (
                "deepseek-flash",
                "DeepSeek V4.1-Flash",
                "Flash 模型",
                "DeepSeek",
                ModelProvider::DeepSeek,
            ),
            (
                "deepseek-pro",
                "DeepSeek V4-Pro",
                "Pro 模型",
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
        for model in ClaudeModel::ALL {
            models.push(json!({"id":model.selection_id(), "name":model.name(),
                "subtitle":model.subtitle(), "provider":"Claude",
                "configured":self.credentials.key(ModelProvider::Claude).is_ok()}));
        }
        models.push(json!({"id":"mixture", "name":"Mixture混合模式", "subtitle":"自由组队 · 分工协作", "provider":"Mixture", "configured":true}));
        json!({"type": "ready", "protocol": 2, "models": models, "credentials": self.credentials.status()})
    }

    pub async fn handle(&mut self, request: Request) -> Value {
        self.handle_with_notice(request, &mut |_| {}).await
    }

    pub async fn handle_with_notice(
        &mut self,
        request: Request,
        notice: &mut dyn FnMut(&str),
    ) -> Value {
        self.handle_with_events(request, notice, &mut |_| {}).await
    }

    pub async fn handle_with_events(
        &mut self,
        request: Request,
        notice: &mut dyn FnMut(&str),
        progress: &mut dyn FnMut(crate::progress::ProgressEvent),
    ) -> Value {
        match self.execute(request, notice, progress).await {
            Ok(response) => response,
            Err(message) => json!({"type": "error", "message": self.credentials.redact(&message)}),
        }
    }

    async fn execute(
        &mut self,
        request: Request,
        notice: &mut dyn FnMut(&str),
        progress: &mut dyn FnMut(crate::progress::ProgressEvent),
    ) -> Result<Value, String> {
        match request {
            Request::SelectModel { model_id } => {
                if model_id == "mixture" {
                    if self.mixture.is_none() {
                        self.mixture =
                            Some(crate::mixture::Mixture::new(&self.credentials, notice)?);
                    }
                    self.model_id = Some(model_id);
                    return Ok(
                        json!({"type":"selected","model_id":"mixture","model":self.mixture.as_ref().unwrap().description(),"provider":"Mixture","web_search":false}),
                    );
                }
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
            Request::ConfigureMixture {
                models,
                members,
                allow_role_fallback,
            } => {
                let mut next = match (models, members) {
                    (Some(models), None) => {
                        crate::mixture::Mixture::selected(&self.credentials, &models, notice)?
                    }
                    (None, Some(members)) => crate::mixture::Mixture::configured(
                        &self.credentials,
                        members,
                        allow_role_fallback,
                        notice,
                    )?,
                    _ => {
                        return Err(
                            "请提交成员角色配置，或兼容模式模型列表；不能同时提交两者".into()
                        );
                    }
                };
                if let Some(previous) = &self.mixture {
                    next.inherit_history(previous);
                }
                let description = next.description();
                let configuration = next.configuration();
                self.mixture = Some(next);
                self.model_id = Some("mixture".into());
                Ok(
                    json!({"type":"selected", "model_id":"mixture", "model":description, "provider":"Mixture", "web_search":false, "configuration":configuration}),
                )
            }
            Request::Chat { message } => {
                if message.trim().is_empty() {
                    return Err("请输入聊天内容".into());
                }
                if self.model_id.as_deref() == Some("mixture") {
                    let mixture = self.mixture.as_mut().ok_or("混合模式尚未初始化")?;
                    let text = mixture
                        .chat_with_progress(&message, notice, progress)
                        .await?;
                    return Ok(
                        json!({"type":"reply","text":text,"complete":mixture.last_completed(),"model":"Mixture","provider":"Mixture","model_id":"mixture"}),
                    );
                }
                let agent = self.agent.as_mut().ok_or("请先选择一个模型")?;
                let started = std::time::Instant::now();
                let starting_model = if self.model_id.as_deref() == Some("gemini-auto") {
                    GeminiMode::Auto
                        .model_for_task(&message, agent.model())
                        .to_string()
                } else {
                    agent.model().to_string()
                };
                progress(crate::progress::ProgressEvent::new(
                    "response",
                    "Worker",
                    &starting_model,
                    "running",
                    "正在生成回复",
                    0,
                ));
                let result = agent.chat_text(&message).await;
                progress(crate::progress::ProgressEvent::new(
                    "response",
                    "Worker",
                    agent.model(),
                    if result.is_ok() {
                        "completed"
                    } else {
                        "failed"
                    },
                    if result.is_ok() {
                        "回复已生成"
                    } else {
                        "模型请求失败"
                    },
                    started.elapsed().as_millis() as u64,
                ));
                let text = result?;
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
                claude,
                plaintext_acknowledged,
            } => {
                self.credentials
                    .save([deepseek, openai, gemini, claude], plaintext_acknowledged)?;
                self.agent = None;
                self.mixture = None;
                self.model_id = None;
                Ok(json!({"type": "credentials", "credentials": self.credentials.status()}))
            }
            Request::DeleteCredentials { provider } => {
                self.credentials.delete(&provider)?;
                self.agent = None;
                self.mixture = None;
                self.model_id = None;
                Ok(json!({"type": "credentials", "credentials": self.credentials.status()}))
            }
            Request::Reset => {
                if let Some(mixture) = self.mixture.as_mut() {
                    mixture.reset();
                }
                if self.model_id.as_deref() == Some("mixture") {
                    return Ok(json!({"type":"reset"}));
                }
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

    #[tokio::test]
    async fn custom_team_roundtrips_and_invalid_changes_preserve_the_previous_team() {
        let directory =
            std::env::temp_dir().join(format!("nautilus-custom-roles-{}", std::process::id()));
        let mut session = ChatSession::new(CredentialStore::open(directory.clone()).unwrap());
        let save: Request = serde_json::from_value(json!({"type":"save_credentials", "deepseek":"offline-ds", "claude":"offline-cl", "plaintext_acknowledged":true})).unwrap();
        assert_eq!(session.handle(save).await["type"], "credentials");
        let members = json!([
            {"model_id":"claude-sonnet","stage":"planner","role":" 自定义架构师🌊 ","goal":" 规划可验证的工作节点 "},
            {"model_id":"deepseek-pro","stage":"summarizer","role":"最终编辑","goal":"生成中文最终文稿"}
        ]);
        let request: Request =
            serde_json::from_value(json!({"type":"configure_mixture", "members":members})).unwrap();
        let selected = session.handle(request).await;
        assert_eq!(selected["type"], "selected");
        assert_eq!(selected["configuration"]["allow_role_fallback"], false);
        assert_eq!(
            selected["configuration"]["members"][0]["role"],
            "自定义架构师🌊"
        );
        assert_eq!(
            selected["configuration"]["members"][0]["goal"],
            "规划可验证的工作节点"
        );
        let previous = session.mixture.as_ref().unwrap().configuration();
        for invalid in [
            json!({"type":"configure_mixture", "members":[{"model_id":"claude-sonnet","stage":"worker","role":"role","goal":"goal"}]}),
            json!({"type":"configure_mixture", "members":members, "models":["deepseek-pro"]}),
            json!({"type":"configure_mixture", "members":[{"model_id":"gemini-flash","stage":"summarizer","role":"role","goal":"goal"}]}),
        ] {
            assert_eq!(
                session
                    .handle(serde_json::from_value(invalid).unwrap())
                    .await["type"],
                "error"
            );
            assert_eq!(session.mixture.as_ref().unwrap().configuration(), previous);
        }
        assert!(serde_json::from_value::<Request>(json!({"type":"configure_mixture", "members":[{"model_id":"deepseek-pro","stage":"invented","role":"role","goal":"goal"}]})).is_err());
        session
            .handle(Request::DeleteCredentials {
                provider: "all".into(),
            })
            .await;
        std::fs::remove_dir_all(directory).unwrap();
    }
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
    fn catalog_has_all_eleven_models_and_protocol_rejects_tool_commands() {
        assert_eq!(session().ready()["models"].as_array().unwrap().len(), 11);
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

    #[tokio::test]
    async fn claude_requires_user_key_and_every_preset_selects_its_exact_api_model() {
        let directory =
            std::env::temp_dir().join(format!("nautilus-claude-profile-{}", std::process::id()));
        let mut session = ChatSession::new(CredentialStore::open(directory.clone()).unwrap());
        for model in ClaudeModel::ALL {
            assert_eq!(
                session
                    .handle(Request::SelectModel {
                        model_id: model.selection_id().into()
                    })
                    .await["type"],
                "error"
            );
        }
        let save: Request = serde_json::from_value(json!({"type":"save_credentials", "claude":"offline-claude", "plaintext_acknowledged":true})).unwrap();
        assert_eq!(session.handle(save).await["credentials"]["claude"], true);
        for model in ClaudeModel::ALL {
            let selected = session
                .handle(Request::SelectModel {
                    model_id: model.selection_id().into(),
                })
                .await;
            assert_eq!(selected["type"], "selected");
            assert_eq!(selected["model"], model.api_id());
            assert_eq!(selected["provider"], "Claude");
        }
        assert_eq!(session.handle(Request::Reset).await["type"], "reset");
        assert_eq!(
            session
                .handle(Request::DeleteCredentials {
                    provider: "claude".into()
                })
                .await["credentials"]["claude"],
            false
        );
        assert!(session.agent.is_none());
        std::fs::remove_dir_all(directory).unwrap();
    }
}
