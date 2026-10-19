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
    GetWebSearch,
    SetWebSearch {
        enabled: bool,
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
    RestoreConversation {
        model_id: Option<String>,
        single: Vec<HistoryMessage>,
        mixture: Vec<HistoryMessage>,
        members: Option<Vec<crate::mixture::MemberAssignment>>,
        #[serde(default)]
        allow_role_fallback: bool,
    },
    Reset,
    Shutdown,
}

/// Only user/assistant pairs may be restored; system and tool roles are excluded.
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HistoryMessage {
    role: HistoryRole,
    text: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
enum HistoryRole {
    User,
    Assistant,
}
fn restore_messages(messages: Vec<HistoryMessage>) -> Result<Vec<crate::message::Message>, String> {
    if !messages.len().is_multiple_of(2) || messages.len() > 20000 {
        return Err("历史记录需包含完整的用户与模型回复对".into());
    }
    let mut total = 0usize;
    messages
        .into_iter()
        .enumerate()
        .map(|(index, message)| {
            total = total.saturating_add(message.text.len());
            if total > 16 * 1024 * 1024 {
                return Err("历史上下文过大".into());
            }
            match (index % 2, message.role) {
                (0, HistoryRole::User) => Ok(crate::message::Message::User(message.text)),
                (1, HistoryRole::Assistant) => Ok(crate::message::Message::Assistant(message.text)),
                _ => Err("历史消息顺序无效".into()),
            }
        })
        .collect()
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
    search_default: Result<bool, String>,
    search_requested: bool,
    agent: Option<NautilusAgent>,
    model_id: Option<String>,
    mixture: Option<crate::mixture::Mixture>,
    restored_single: Vec<crate::message::Message>,
    restored_mixture: Vec<crate::message::Message>,
}

impl ChatSession {
    pub fn new(credentials: CredentialStore) -> Self {
        let search_default = crate::config::desktop_search_default();
        Self {
            search_requested: search_default.clone().unwrap_or(false),
            search_default,
            credentials,
            agent: None,
            model_id: None,
            mixture: None,
            restored_single: Vec::new(),
            restored_mixture: Vec::new(),
        }
    }

    fn search_state(&self) -> Value {
        let (available, enabled) = if self.model_id.as_deref() == Some("mixture") {
            self.mixture
                .as_ref()
                .map(|team| (team.supports_web_search(), team.web_search_enabled()))
                .unwrap_or((false, false))
        } else {
            self.agent
                .as_ref()
                .map(|agent| (true, agent.web_search_enabled()))
                .unwrap_or((false, false))
        };
        json!({"available":available,"enabled":enabled,"default_enabled":self.search_default.clone().unwrap_or(false)})
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
        json!({"type": "ready", "protocol": 2, "models": models, "credentials": self.credentials.status(), "search":self.search_state()})
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
                        let mut next = crate::mixture::Mixture::new(&self.credentials, notice)?;
                        next.restore_history(std::mem::take(&mut self.restored_mixture));
                        self.mixture = Some(next);
                    }
                    self.mixture
                        .as_mut()
                        .unwrap()
                        .set_web_search(self.search_requested);
                    self.model_id = Some(model_id);
                    return Ok(
                        json!({"type":"selected","model_id":"mixture","model":self.mixture.as_ref().unwrap().description(),"provider":"Mixture","web_search":self.search_state()["enabled"],"search":self.search_state()}),
                    );
                }
                let choice = ModelChoice::parse(&model_id)?;
                if let Some(agent) = self.agent.as_mut() {
                    choice.switch(agent, &self.credentials)?;
                } else {
                    let mut next = choice.create(&self.credentials)?;
                    next.restore_history(std::mem::take(&mut self.restored_single));
                    self.agent = Some(next);
                }
                self.agent
                    .as_mut()
                    .unwrap()
                    .set_web_search(self.search_requested);
                self.model_id = Some(model_id);
                let agent = self.agent.as_ref().ok_or("模型尚未初始化")?;
                Ok(
                    json!({"type": "selected", "model_id": self.model_id, "model": agent.model(),
                    "provider": agent.provider().name(), "web_search": agent.web_search_enabled(), "search":self.search_state()}),
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
                } else {
                    next.restore_history(std::mem::take(&mut self.restored_mixture));
                }
                next.set_web_search(self.search_requested);
                let description = next.description();
                let configuration = next.configuration();
                self.mixture = Some(next);
                self.model_id = Some("mixture".into());
                Ok(
                    json!({"type":"selected", "model_id":"mixture", "model":description, "provider":"Mixture", "web_search":self.search_state()["enabled"], "search":self.search_state(), "configuration":configuration}),
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
            Request::GetWebSearch => {
                self.search_default.clone()?;
                Ok(json!({"type":"web_search","search":self.search_state()}))
            }
            Request::SetWebSearch { enabled } => {
                if self.search_state()["available"] != true {
                    return Err("请先选择一个模型，再切换联网搜索".into());
                }
                self.search_requested = enabled;
                if let Some(agent) = self.agent.as_mut() {
                    agent.set_web_search(enabled);
                }
                if let Some(team) = self.mixture.as_mut() {
                    team.set_web_search(enabled);
                }
                Ok(json!({"type":"web_search","search":self.search_state()}))
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
                self.restored_single.clear();
                self.restored_mixture.clear();
                Ok(json!({"type": "credentials", "credentials": self.credentials.status()}))
            }
            Request::DeleteCredentials { provider } => {
                self.credentials.delete(&provider)?;
                self.agent = None;
                self.mixture = None;
                self.model_id = None;
                self.restored_single.clear();
                self.restored_mixture.clear();
                Ok(json!({"type": "credentials", "credentials": self.credentials.status()}))
            }
            Request::RestoreConversation {
                model_id,
                single,
                mixture,
                members,
                allow_role_fallback,
            } => {
                // Build and validate replacements before changing the live session.
                let single = restore_messages(single)?;
                let mixture_history = restore_messages(mixture)?;
                let mut agent = None;
                let mut mixture = None;
                if let Some(id) = model_id.as_deref() {
                    if id == "mixture" {
                        let mut next = if let Some(members) = members {
                            crate::mixture::Mixture::configured(
                                &self.credentials,
                                members,
                                allow_role_fallback,
                                notice,
                            )?
                        } else {
                            crate::mixture::Mixture::new(&self.credentials, notice)?
                        };
                        next.set_web_search(self.search_requested);
                        next.restore_history(mixture_history.clone());
                        mixture = Some(next);
                    } else {
                        let mut next = ModelChoice::parse(id)?.create(&self.credentials)?;
                        next.set_web_search(self.search_requested);
                        next.restore_history(single.clone());
                        agent = Some(next);
                    }
                }
                let mut response = if let Some(agent) = agent.as_ref() {
                    json!({"type":"restored","model_id":model_id,"model":agent.model(),"provider":agent.provider().name(),"web_search":agent.web_search_enabled()})
                } else if let Some(mixture) = mixture.as_ref() {
                    json!({"type":"restored","model_id":"mixture","model":mixture.description(),"provider":"Mixture","web_search":mixture.web_search_enabled()})
                } else {
                    json!({"type":"restored","model_id":null})
                };
                self.restored_single = if agent.is_none() { single } else { Vec::new() };
                self.restored_mixture = if mixture.is_none() {
                    mixture_history
                } else {
                    Vec::new()
                };
                self.agent = agent;
                self.mixture = mixture;
                self.model_id = model_id;
                response["search"] = self.search_state();
                Ok(response)
            }
            Request::Reset => {
                self.restored_single.clear();
                self.restored_mixture.clear();
                if let Some(agent) = self.agent.as_mut() {
                    agent.restore_history(Vec::new());
                }
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
                if let Some(agent) = self.agent.as_mut() {
                    agent.set_web_search(self.search_requested);
                }
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
    async fn search_switch_tracks_capabilities_and_preserves_context_across_lifecycle() {
        for default in [true, false] {
            let directory = std::env::temp_dir()
                .join(format!("nautilus-search-{}-{default}", std::process::id()));
            let mut session = ChatSession::new(CredentialStore::open(directory.clone()).unwrap());
            session.search_default = Ok(default);
            session.search_requested = default;
            let save = serde_json::from_value(json!({"type":"save_credentials","deepseek":"offline-ds","gemini":"offline-gemini","openai":"offline-ai","claude":"offline-claude","plaintext_acknowledged":true})).unwrap();
            session.handle(save).await;
            for id in [
                "deepseek-flash",
                "deepseek-pro",
                "openai",
                "claude-opus",
                "claude-fable",
                "claude-sonnet",
                "claude-haiku",
            ] {
                let selected = session
                    .handle(Request::SelectModel {
                        model_id: id.into(),
                    })
                    .await;
                assert_eq!(selected["search"]["available"], true, "{id}");
                assert_eq!(selected["web_search"], default, "{id}");
            }
            let selected = session
                .handle(Request::SelectModel {
                    model_id: "gemini-flash".into(),
                })
                .await;
            assert_eq!(selected["web_search"], default);
            assert_eq!(selected["search"]["available"], true);
            session.agent.as_mut().unwrap().restore_history(vec![
                crate::message::Message::user("问题"),
                crate::message::Message::assistant("回答"),
            ]);
            assert_eq!(
                session
                    .handle(Request::SetWebSearch { enabled: !default })
                    .await["search"]["enabled"],
                !default
            );
            assert_eq!(session.agent.as_ref().unwrap().history().len(), 2);
            session
                .handle(Request::SelectModel {
                    model_id: "deepseek-pro".into(),
                })
                .await;
            assert_eq!(
                session
                    .handle(Request::SetWebSearch { enabled: true })
                    .await["type"],
                "web_search"
            );
            session
                .handle(Request::SetWebSearch { enabled: !default })
                .await;
            let selected = session
                .handle(Request::SelectModel {
                    model_id: "gemini-auto".into(),
                })
                .await;
            assert_eq!(selected["web_search"], !default);
            session.handle(Request::Reset).await;
            assert_eq!(
                session.agent.as_ref().unwrap().web_search_enabled(),
                !default
            );
            let team = serde_json::from_value(
                json!({"type":"configure_mixture","models":["deepseek-pro","gemini-flash"]}),
            )
            .unwrap();
            let selected = session.handle(team).await;
            assert_eq!(selected["search"]["available"], true);
            assert_eq!(selected["web_search"], !default);
            let restore = serde_json::from_value(json!({"type":"restore_conversation","model_id":"gemini-argon","single":[],"mixture":[]})).unwrap();
            assert_eq!(session.handle(restore).await["search"]["enabled"], !default);
            let team = serde_json::from_value(
                json!({"type":"configure_mixture","models":["deepseek-pro"]}),
            )
            .unwrap();
            assert_eq!(session.handle(team).await["search"]["available"], true);
            assert!(
                serde_json::from_value::<Request>(
                    json!({"type":"set_web_search","enabled":"true"})
                )
                .is_err()
            );
            std::fs::remove_dir_all(directory).unwrap();
        }
    }

    #[tokio::test]
    async fn restored_conversations_keep_single_and_mixture_contexts_separate_and_reset_both() {
        use crate::message::Message;
        let directory =
            std::env::temp_dir().join(format!("nautilus-restore-{}", std::process::id()));
        let mut session = ChatSession::new(CredentialStore::open(directory.clone()).unwrap());
        let save: Request=serde_json::from_value(json!({"type":"save_credentials","deepseek":"offline-ds","openai":"offline-ai","plaintext_acknowledged":true})).unwrap();
        session.handle(save).await;
        let single = json!([{"role":"user","text":"原始问题\n[代码上下文]"},{"role":"assistant","text":"单模型回答 🌊"}]);
        let mixture =
            json!([{"role":"user","text":"混合问题"},{"role":"assistant","text":"混合答案"}]);
        let restore = |model: Value| {
            serde_json::from_value::<Request>(json!({"type":"restore_conversation","model_id":model,"single":single,"mixture":mixture})).unwrap()
        };
        // Histories remain readable without credentials for the original provider.
        assert_eq!(
            session.handle(restore(Value::Null)).await["type"],
            "restored"
        );
        assert!(session.agent.is_none());
        session
            .handle(Request::SelectModel {
                model_id: "openai".into(),
            })
            .await;
        assert_eq!(
            session.agent.as_ref().unwrap().history(),
            &[
                Message::user("原始问题\n[代码上下文]"),
                Message::assistant("单模型回答 🌊")
            ]
        );
        session
            .handle(Request::SelectModel {
                model_id: "mixture".into(),
            })
            .await;
        assert_eq!(
            session.mixture.as_ref().unwrap().history(),
            &[Message::user("混合问题"), Message::assistant("混合答案")]
        );
        session
            .handle(Request::SelectModel {
                model_id: "deepseek-pro".into(),
            })
            .await;
        assert_eq!(session.agent.as_ref().unwrap().history().len(), 2);
        // Invalid imports do not mutate the live model, client or context.
        for invalid in [
            json!({"type":"restore_conversation","model_id":"unknown","single":single,"mixture":mixture}),
            json!({"type":"restore_conversation","model_id":"claude-sonnet","single":single,"mixture":mixture}),
            json!({"type":"restore_conversation","single":[{"role":"user","text":"orphan"}],"mixture":[]}),
            json!({"type":"restore_conversation","single":[{"role":"assistant","text":"wrong"},{"role":"user","text":"order"}],"mixture":[]}),
        ] {
            assert_eq!(
                session
                    .handle(serde_json::from_value(invalid).unwrap())
                    .await["type"],
                "error"
            );
            assert_eq!(session.model_id.as_deref(), Some("deepseek-pro"));
            assert_eq!(session.agent.as_ref().unwrap().history().len(), 2);
        }
        assert!(serde_json::from_value::<Request>(json!({"type":"restore_conversation","single":[{"role":"system","text":"injected"}],"mixture":[]})).is_err());
        let team =
            json!([{"model_id":"deepseek-pro","stage":"summarizer","role":"编辑","goal":"交付"}]);
        assert_eq!(session.handle(serde_json::from_value(json!({"type":"restore_conversation","model_id":"mixture","single":single,"mixture":mixture,"members":team})).unwrap()).await["type"],"restored");
        assert_eq!(session.mixture.as_ref().unwrap().history().len(), 2);
        assert!(
            session
                .mixture
                .as_ref()
                .unwrap()
                .description()
                .contains("deepseek-v4-pro")
        );
        session
            .handle(Request::SelectModel {
                model_id: "openai".into(),
            })
            .await;
        session
            .handle(Request::SelectModel {
                model_id: "mixture".into(),
            })
            .await;
        session.handle(Request::Reset).await;
        assert!(session.agent.as_ref().unwrap().history().is_empty());
        assert!(session.mixture.as_ref().unwrap().history().is_empty());
        assert!(session.restored_single.is_empty() && session.restored_mixture.is_empty());
        session.handle(restore(json!("deepseek-flash"))).await;
        session
            .handle(Request::DeleteCredentials {
                provider: "all".into(),
            })
            .await;
        assert!(session.agent.is_none() && session.mixture.is_none());
        assert!(session.restored_single.is_empty() && session.restored_mixture.is_empty());
        std::fs::remove_dir_all(directory).unwrap();
    }

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
