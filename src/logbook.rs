//! 保存单个 Agent 实例的会话历史。

use crate::message::Message;

#[derive(Default)]
pub struct Logbook {
    messages: Vec<Message>,
}

impl Logbook {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn push(&mut self, msg: Message) {
        self.messages.push(msg);
    }
    pub fn get_history(&self) -> &[Message] {
        &self.messages
    }
}
