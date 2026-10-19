//! Observable work milestones, never hidden reasoning or invented token percentages.
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct ProgressEvent {
    pub step_id: String,
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub role_title: Option<String>,
    pub model: String,
    pub status: String,
    pub message: String,
    pub elapsed_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub node_count: Option<usize>,
}
impl ProgressEvent {
    pub fn new(
        step: &str,
        role: &str,
        model: &str,
        status: &str,
        message: &str,
        elapsed_ms: u64,
    ) -> Self {
        Self {
            step_id: step.into(),
            role: role.into(),
            role_title: None,
            model: model.into(),
            status: status.into(),
            message: message.into(),
            elapsed_ms,
            node_count: None,
        }
    }
}
