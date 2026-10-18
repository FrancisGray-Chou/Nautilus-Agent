//! 模型服务配置；调用方负责在读取配置前加载 .env。

pub const DEEPSEEK_API_BASE: &str = "https://api.deepseek.com";
pub const DEFAULT_DEEPSEEK_MODEL: &str = "deepseek-flash";
pub const GEMINI_FLASH_MODEL: &str = "gemini-3.8-flash";
pub const GEMINI_ARGON_MODEL: &str = "gemini-4-argon";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GeminiMode {
    FlashSearch,
    Argon,
    Auto,
}

impl GeminiMode {
    pub fn from_number(input: &str) -> Option<Self> {
        match input.trim() {
            "1" => Some(Self::FlashSearch),
            "2" => Some(Self::Argon),
            "3" => Some(Self::Auto),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::FlashSearch => "3.8 Flash（Extended Thinking + Search）",
            Self::Argon => "4 Argon（思考 + Search）",
            Self::Auto => "自动（按任务选择 Flash / Argon）",
        }
    }

    pub fn model_for_task<'a>(self, input: &str, previous_model: &'a str) -> &'a str {
        match self {
            Self::FlashSearch => GEMINI_FLASH_MODEL,
            Self::Argon => GEMINI_ARGON_MODEL,
            Self::Auto => {
                let input = input.trim().to_lowercase();
                if matches!(input.as_str(), "继续" | "继续处理" | "continue" | "go on") {
                    return previous_model;
                }
                let complex = [
                    "证明",
                    "推导",
                    "算法",
                    "调试",
                    "重构",
                    "架构",
                    "数学",
                    "复杂",
                    "优化",
                    "多步骤",
                    "推理",
                    "proof",
                    "prove",
                    "derive",
                    "algorithm",
                    "debug",
                    "refactor",
                    "architecture",
                    "complex",
                    "optimiz",
                    "reasoning",
                    "step by step",
                ];
                if input.contains("```")
                    || input.chars().count() >= 1200
                    || complex.iter().any(|word| input.contains(word))
                {
                    GEMINI_ARGON_MODEL
                } else {
                    GEMINI_FLASH_MODEL
                }
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelProvider {
    DeepSeek,
    OpenAI,
    Gemini,
}

impl ModelProvider {
    pub fn from_number(input: &str) -> Option<Self> {
        match input.trim() {
            "1" => Some(Self::DeepSeek),
            "2" => Some(Self::OpenAI),
            "3" => Some(Self::Gemini),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::DeepSeek => "DeepSeek",
            Self::OpenAI => "OpenAI",
            Self::Gemini => "Gemini",
        }
    }

    pub fn api_base(self) -> &'static str {
        match self {
            Self::DeepSeek => DEEPSEEK_API_BASE,
            Self::OpenAI => "https://api.openai.com/v1",
            Self::Gemini => "https://generativelanguage.googleapis.com/v1beta/openai",
        }
    }

    fn env_names(self) -> (&'static str, &'static str, &'static str) {
        match self {
            Self::DeepSeek => ("DEEPSEEK_API_KEY", "DEEPSEEK_MODEL", DEFAULT_DEEPSEEK_MODEL),
            Self::OpenAI => ("OPENAI_API_KEY", "OPENAI_MODEL", "gpt-5-mini"),
            Self::Gemini => ("GEMINI_API_KEY", "GEMINI_MODEL", "gemini-3.8-flash"),
        }
    }
}

pub struct LLMConfig {
    pub(crate) provider: ModelProvider,
    pub(crate) api_key: String,
    pub(crate) model: String,
    pub(crate) web_search: bool,
}

impl LLMConfig {
    pub(crate) fn for_user_key(
        provider: ModelProvider,
        key: &str,
        mode: Option<GeminiMode>,
    ) -> Result<Self, String> {
        let (key_name, _, _) = provider.env_names();
        let lookup = |name: &str| {
            if name == key_name {
                Some(key.to_string())
            } else {
                std::env::var(name).ok()
            }
        };
        match mode {
            Some(mode) => Self::from_gemini_lookup(mode, lookup),
            None => Self::from_lookup(provider, lookup),
        }
    }
    pub fn from_env(provider: ModelProvider) -> Result<Self, String> {
        Self::from_lookup(provider, |name| std::env::var(name).ok())
    }

    /// 菜单预设明确指定模型并开启搜索，不受旧的 GEMINI_MODEL 配置影响。
    pub fn for_gemini_mode(mode: GeminiMode) -> Result<Self, String> {
        Self::from_gemini_lookup(mode, |name| std::env::var(name).ok())
    }

    fn from_gemini_lookup(
        mode: GeminiMode,
        lookup: impl Fn(&str) -> Option<String>,
    ) -> Result<Self, String> {
        Self::from_lookup(ModelProvider::Gemini, |name| match name {
            "GEMINI_MODEL" => Some(mode.model_for_task("", GEMINI_FLASH_MODEL).to_string()),
            "GEMINI_WEB_SEARCH" => Some("true".into()),
            _ => lookup(name),
        })
    }

    fn from_lookup(
        provider: ModelProvider,
        lookup: impl Fn(&str) -> Option<String>,
    ) -> Result<Self, String> {
        let (key_name, model_name, default_model) = provider.env_names();
        let api_key =
            lookup(key_name).ok_or_else(|| format!("请在 .env 或环境变量中设置 {key_name}"))?;
        if api_key.trim().is_empty() {
            return Err(format!(
                "{key_name} 不能为空，请填写 {} API 密钥",
                provider.name()
            ));
        }
        let model = lookup(model_name).unwrap_or_else(|| default_model.to_string());
        if model.trim().is_empty() {
            return Err(format!("{model_name} 不能为空"));
        }
        let web_search = if provider == ModelProvider::Gemini {
            match lookup("GEMINI_WEB_SEARCH").as_deref().map(str::trim) {
                None | Some("true" | "1") => true,
                Some("false" | "0") => false,
                _ => return Err("GEMINI_WEB_SEARCH 必须为 true/false 或 1/0".into()),
            }
        } else {
            false
        };
        Ok(Self {
            provider,
            api_key: api_key.trim().to_string(),
            model: model.trim().to_string(),
            web_search,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gemini_presets_override_old_model_settings_and_enable_search() {
        for (mode, model) in [
            (GeminiMode::FlashSearch, GEMINI_FLASH_MODEL),
            (GeminiMode::Argon, GEMINI_ARGON_MODEL),
            (GeminiMode::Auto, GEMINI_FLASH_MODEL),
        ] {
            let config = LLMConfig::from_gemini_lookup(mode, |name| {
                Some(
                    if name == "GEMINI_API_KEY" {
                        "test-key"
                    } else {
                        "invalid-old-setting"
                    }
                    .into(),
                )
            })
            .unwrap();
            assert_eq!(config.model, model);
            assert!(config.web_search);
        }
        assert!(LLMConfig::from_gemini_lookup(GeminiMode::Auto, |_| None).is_err());
    }

    #[test]
    fn auto_routes_general_and_complex_tasks_and_preserves_followups() {
        assert_eq!(
            GeminiMode::Auto.model_for_task("搜索今天的新闻", GEMINI_ARGON_MODEL),
            GEMINI_FLASH_MODEL
        );
        for input in [
            "证明这个定理",
            "Debug this Rust algorithm",
            "```rust\nfn main() {}\n```",
        ] {
            assert_eq!(
                GeminiMode::Auto.model_for_task(input, GEMINI_FLASH_MODEL),
                GEMINI_ARGON_MODEL
            );
        }
        assert_eq!(
            GeminiMode::Auto.model_for_task(&"a".repeat(1200), GEMINI_FLASH_MODEL),
            GEMINI_ARGON_MODEL
        );
        assert_eq!(
            GeminiMode::Auto.model_for_task("继续", GEMINI_ARGON_MODEL),
            GEMINI_ARGON_MODEL
        );
        assert_eq!(
            GeminiMode::FlashSearch.model_for_task("证明这个定理", GEMINI_ARGON_MODEL),
            GEMINI_FLASH_MODEL
        );
        assert_eq!(
            GeminiMode::Argon.model_for_task("你好", GEMINI_FLASH_MODEL),
            GEMINI_ARGON_MODEL
        );
    }

    #[test]
    fn menu_numbers_map_to_providers() {
        for (number, provider) in [
            ("1", ModelProvider::DeepSeek),
            ("2", ModelProvider::OpenAI),
            ("3", ModelProvider::Gemini),
        ] {
            assert_eq!(ModelProvider::from_number(number), Some(provider));
        }
        assert_eq!(ModelProvider::from_number("4"), None);
    }

    #[test]
    fn each_provider_uses_only_its_own_key_and_default_model() {
        for provider in [
            ModelProvider::DeepSeek,
            ModelProvider::OpenAI,
            ModelProvider::Gemini,
        ] {
            let (key_name, _, default_model) = provider.env_names();
            let config = LLMConfig::from_lookup(provider, |name| {
                (name == key_name).then(|| " test-key ".into())
            })
            .unwrap();
            assert_eq!(config.api_key, "test-key");
            assert_eq!(config.model, default_model);
            assert!(LLMConfig::from_lookup(provider, |_| None).is_err());
            assert!(LLMConfig::from_lookup(provider, |_| Some(" ".into())).is_err());
        }
    }

    #[test]
    fn custom_model_is_trimmed_and_blank_model_is_rejected() {
        let provider = ModelProvider::Gemini;
        let config = LLMConfig::from_lookup(provider, |name| {
            (name != "GEMINI_WEB_SEARCH").then(|| {
                if name == "GEMINI_MODEL" {
                    " custom-model "
                } else {
                    "key"
                }
                .into()
            })
        })
        .unwrap();
        assert_eq!(config.model, "custom-model");
        assert!(
            LLMConfig::from_lookup(provider, |name| Some(
                if name == "GEMINI_MODEL" { " " } else { "key" }.into()
            ))
            .is_err()
        );
    }

    #[test]
    fn gemini_search_defaults_on_and_can_be_disabled() {
        for (value, expected) in [
            (None, true),
            (Some("true"), true),
            (Some("false"), false),
            (Some("0"), false),
        ] {
            let config = LLMConfig::from_lookup(ModelProvider::Gemini, |name| match name {
                "GEMINI_API_KEY" => Some("test-key".into()),
                "GEMINI_WEB_SEARCH" => value.map(str::to_string),
                _ => None,
            })
            .unwrap();
            assert_eq!(config.web_search, expected);
        }
        assert!(
            LLMConfig::from_lookup(ModelProvider::Gemini, |name| match name {
                "GEMINI_API_KEY" => Some("test-key".into()),
                "GEMINI_WEB_SEARCH" => Some("invalid".into()),
                _ => None,
            })
            .is_err()
        );
    }
}
