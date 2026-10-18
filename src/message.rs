//! Agent 内部的会话消息。

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
    User(String),
    Assistant(String),
    ToolResult(String, String),
}

impl Message {
    pub fn user(content: &str) -> Self {
        Message::User(content.to_string())
    }
    pub fn assistant(content: &str) -> Self {
        Message::Assistant(content.to_string())
    }
    pub fn tool_result(name: String, result: String) -> Self {
        Message::ToolResult(name, result)
    }
}
