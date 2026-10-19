//! Local, authenticated HTTP SSE transport. No dependencies beyond Tokio.
use crate::bridge::{ChatSession, Request};
use serde_json::{Value, json};
use std::io;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub fn frame(event: &str, value: &Value) -> String {
    // All event names come from this module, never from model output.
    format!("event: {event}\ndata: {}\n\n", value)
}
async fn write_event(
    output: &mut (impl AsyncWrite + Unpin),
    event: &str,
    value: &Value,
) -> io::Result<()> {
    output.write_all(frame(event, value).as_bytes()).await?;
    output.flush().await
}
async fn read_request(
    input: &mut (impl AsyncRead + Unpin),
    token: &str,
) -> Result<Request, String> {
    let mut data = Vec::new();
    let header_end = loop {
        if data.len() > 16384 {
            return Err("请求头过大".into());
        }
        let mut byte = [0u8; 1];
        if input.read(&mut byte).await.map_err(|_| "读取请求失败")? == 0 {
            return Err("请求已关闭".into());
        }
        data.push(byte[0]);
        if data.ends_with(b"\r\n\r\n") {
            break data.len();
        }
    };
    let headers = std::str::from_utf8(&data).map_err(|_| "请求头无效")?;
    let mut lines = headers.split("\r\n");
    if lines.next() != Some("POST /command HTTP/1.1") {
        return Err("仅支持 POST /command".into());
    }
    let mut length = None;
    let mut authorized = false;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("authorization") {
                authorized = value.trim() == format!("Bearer {token}");
            }
            if name.eq_ignore_ascii_case("content-length") {
                if length.is_some() {
                    return Err("重复内容长度".into());
                }
                length = Some(value.trim().parse::<usize>().map_err(|_| "内容长度无效")?);
            }
            if name.eq_ignore_ascii_case("transfer-encoding") {
                return Err("不支持分块请求".into());
            }
        }
    }
    if !authorized {
        return Err("本地会话验证失败".into());
    }
    let len = length
        .filter(|n| *n > 0 && *n <= 1_048_576)
        .ok_or("请求内容过大或为空")?;
    data.resize(header_end + len, 0);
    input
        .read_exact(&mut data[header_end..])
        .await
        .map_err(|_| "请求内容不完整")?;
    serde_json::from_slice(&data[header_end..]).map_err(|_| "无效请求；请检查消息类型和字段".into())
}
async fn serve_connection(
    stream: tokio::net::TcpStream,
    session: &mut ChatSession,
    token: &str,
) -> io::Result<bool> {
    let (mut input, mut output) = stream.into_split();
    let request = match tokio::time::timeout(
        std::time::Duration::from_secs(10),
        read_request(&mut input, token),
    )
    .await
    {
        Ok(Ok(request)) => request,
        _ => {
            output
                .write_all(
                    b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                )
                .await?;
            return Ok(false);
        }
    };
    let shutdown = matches!(request, Request::Shutdown);
    output.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nX-Accel-Buffering: no\r\nConnection: close\r\n\r\n").await?;
    output.flush().await?;
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(String, Value)>();
    let notice_tx = tx.clone();
    let mut notice = move |message: &str| {
        let _ = notice_tx.send(("notice".into(), json!({"type":"notice","message":message})));
    };
    let mut progress = move |event: crate::progress::ProgressEvent| {
        let _ = tx.send((
            "progress".into(),
            json!({"type":"progress","progress":event}),
        ));
    };
    let future = session.handle_with_events(request, &mut notice, &mut progress);
    tokio::pin!(future);
    let mut heartbeat = tokio::time::interval(std::time::Duration::from_secs(1));
    let response = loop {
        tokio::select! {
            biased;
            Some((event,value))=rx.recv()=>write_event(&mut output,&event,&value).await?,
            result=&mut future=>break result,
            _=heartbeat.tick()=>{output.write_all(b": heartbeat\n\n").await?;output.flush().await?;}
        }
    };
    while let Ok((event, value)) = rx.try_recv() {
        write_event(&mut output, &event, &value).await?;
    }
    write_event(&mut output, "result", &response).await?;
    output.shutdown().await?;
    Ok(shutdown)
}

pub async fn run(mut session: ChatSession) -> Result<(), Box<dyn std::error::Error>> {
    let token = std::env::var("NAUTILUS_BRIDGE_TOKEN").map_err(|_| "缺少本地 SSE 会话凭证")?;
    if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("本地会话凭证格式无效".into());
    }
    let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0)).await?;
    let mut ready = session.ready();
    ready["transport"] = "sse".into();
    ready["base_url"] = format!("http://127.0.0.1:{}", listener.local_addr()?.port()).into();
    {
        use std::io::Write;
        let mut output = std::io::stdout().lock();
        writeln!(output, "{ready}")?;
        output.flush()?;
    }
    loop {
        let (stream, _) = listener.accept().await?;
        // A disconnected UI cancels only its pending request; the local process remains usable.
        if let Ok(true) = serve_connection(stream, &mut session, &token).await {
            break;
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn events_preserve_unicode_and_escape_newlines() {
        let value = json!({"text":"思考中💭\nWorker\n"});
        let encoded = frame("progress", &value);
        assert!(encoded.starts_with("event: progress\ndata: "));
        assert!(encoded.ends_with("\n\n"));
        assert_eq!(encoded.lines().count(), 3);
        assert_eq!(
            serde_json::from_str::<Value>(
                encoded
                    .lines()
                    .nth(1)
                    .unwrap()
                    .strip_prefix("data: ")
                    .unwrap()
            )
            .unwrap(),
            value
        );
    }
    #[tokio::test]
    async fn validates_auth_and_length_without_network() {
        for (auth, length, ok) in [
            ("secret", "26", true),
            ("wrong", "26", false),
            ("secret", "9999999", false),
        ] {
            let body = r#"{"type":"get_credentials"}"#;
            assert_eq!(body.len(), 26);
            let request = format!(
                "POST /command HTTP/1.1\r\nAuthorization: Bearer {auth}\r\nContent-Length: {length}\r\n\r\n{body}"
            );
            let mut bytes = request.as_bytes();
            assert_eq!(read_request(&mut bytes, "secret").await.is_ok(), ok);
        }
    }
}
