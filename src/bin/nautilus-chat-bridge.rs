//! Java 桌面端的独立入口；stdout 仅输出 JSON Lines。

use std::io::{self, BufRead, Write};

use nautilus_agent::{
    bridge::{ChatSession, Request},
    credentials::CredentialStore,
};
use serde_json::{Value, json};

fn send(output: &mut impl Write, response: &Value) -> io::Result<()> {
    serde_json::to_writer(&mut *output, response)?;
    writeln!(output)?;
    output.flush()
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut session = ChatSession::new(CredentialStore::current_user()?);
    let mut output = io::stdout().lock();
    send(&mut output, &session.ready())?;
    for line in io::stdin().lock().lines() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        let request = match serde_json::from_str::<Request>(&line) {
            Ok(request) => request,
            Err(_) => {
                send(
                    &mut output,
                    &json!({"type": "error", "message": "无效请求；请检查消息类型和字段"}),
                )?;
                continue;
            }
        };
        let shutdown = matches!(request, Request::Shutdown);
        send(&mut output, &session.handle(request).await)?;
        if shutdown {
            break;
        }
    }
    Ok(())
}
