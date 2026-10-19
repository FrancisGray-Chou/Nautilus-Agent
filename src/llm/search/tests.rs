use super::*;
use crate::config::{ClaudeModel, LLMConfig};
use std::sync::{Arc, Mutex};

fn client(provider: ModelProvider) -> LLMClient {
    LLMClient::from_config(LLMConfig {
        provider,
        api_key: "offline-test".into(),
        model: match provider {
            ModelProvider::DeepSeek => "deepseek-flash",
            ModelProvider::OpenAI => "gpt-5-mini",
            ModelProvider::Claude => "claude-opus-5-5",
            ModelProvider::Gemini => "gemini-3.8-flash",
        }
        .into(),
        web_search: true,
    })
    .unwrap()
}
fn fixtures(client: LLMClient, responses: Vec<Value>) -> (LLMClient, Arc<Mutex<Vec<Value>>>) {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let mut client = client.test_transport(requests.clone(), false);
    client.mock_search_responses = Some(Arc::new(Mutex::new(responses.into())));
    (client, requests)
}
fn history() -> Vec<Message> {
    vec![
        Message::user("先前问题"),
        Message::assistant("先前回答"),
        Message::user("今天的新闻？"),
    ]
}
fn answer() -> Value {
    json!({"choices":[{"message":{"role":"assistant","content":"回答"},"finish_reason":"stop"}]})
}
fn search_call(query: &str) -> Value {
    json!({"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"private reasoning","tool_calls":[{"id":"call-1","type":"function","function":{"name":"web_search","arguments":json!({"query":query}).to_string()}}]},"finish_reason":"tool_calls"}]})
}

#[tokio::test]
async fn deepseek_search_executes_tool_then_resumes_exact_model_and_history() {
    for model in ["deepseek-flash", "deepseek-v4-pro"] {
        let mut configured = client(ModelProvider::DeepSeek);
        configured.model = model.into();
        let (client, requests) = fixtures(
            configured,
            vec![
                search_call("今日新闻 🌊"),
                json!({"results":[{"url":"https://example.org/news","title":"新闻","summary":"最新事实"}]}),
                answer(),
            ],
        );
        let (text, complete) = client.chat_collaborative(&history()).await.unwrap();
        assert!(complete && text.contains("https://example.org/news"));
        let requests = requests.lock().unwrap();
        assert_eq!(requests.len(), 3);
        assert_eq!(requests[0]["model"], model);
        assert_eq!(requests[0]["messages"].as_array().unwrap().len(), 3);
        assert_eq!(requests[0]["tools"][0]["function"]["name"], "web_search");
        assert_eq!(
            requests[1],
            json!({"query":"今日新闻 🌊","count":5,"need_content":false})
        );
        assert_eq!(requests[2]["model"], model);
        assert_eq!(
            requests[2]["messages"][3]["reasoning_content"],
            "private reasoning"
        );
        assert_eq!(requests[2]["messages"][4]["tool_call_id"], "call-1");
        assert!(
            requests[2]["messages"][4]["content"]
                .as_str()
                .unwrap()
                .contains("最新事实")
        );
    }
}

#[tokio::test]
async fn deepseek_rejects_invalid_calls_and_bounds_searches_without_silent_fallback() {
    for query in ["".to_string(), "界".repeat(129)] {
        let (client, requests) =
            fixtures(client(ModelProvider::DeepSeek), vec![search_call(&query)]);
        assert!(client.chat_collaborative(&history()).await.is_err());
        assert_eq!(requests.lock().unwrap().len(), 1);
    }
    let mut repeated = Vec::new();
    for _ in 0..MAX_SEARCHES {
        repeated.push(search_call("新闻"));
        repeated.push(json!({"results":[]}));
    }
    repeated.push(search_call("新闻"));
    let (client, requests) = fixtures(client(ModelProvider::DeepSeek), repeated);
    assert!(
        client
            .chat_collaborative(&history())
            .await
            .unwrap_err()
            .contains("次数上限")
    );
    assert_eq!(requests.lock().unwrap().len(), MAX_SEARCHES * 2 + 1);
    let (client, requests) = fixtures(
        self::client(ModelProvider::DeepSeek),
        vec![
            search_call("新闻"),
            json!({"error":{"message":"private-value"}}),
        ],
    );
    let error = client.chat_collaborative(&history()).await.unwrap_err();
    assert!(!error.contains("private-value"));
    assert_eq!(requests.lock().unwrap().len(), 2);
}

#[tokio::test]
async fn openai_uses_responses_and_exposes_clickable_citations_without_reasoning() {
    let response = json!({"status":"completed","output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"private"}]},{"type":"web_search_call","status":"completed"},{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"事实","annotations":[{"type":"url_citation","url":"https://example.org/fact","title":"来源"},{"type":"url_citation","url":"javascript:bad","title":"bad"}]}]}]});
    let (mut client, requests) = fixtures(client(ModelProvider::OpenAI), vec![response.clone()]);
    let (text, complete) = client.chat_collaborative(&history()).await.unwrap();
    assert!(
        complete
            && text.contains("[来源](<https://example.org/fact>)")
            && !text.contains("private")
            && !text.contains("javascript:")
    );
    let request = requests.lock().unwrap()[0].clone();
    assert_eq!(request["model"], "gpt-5-mini");
    assert_eq!(request["input"].as_array().unwrap().len(), 3);
    assert_eq!(request["tools"], json!([{"type":"web_search"}]));
    assert_eq!(request["store"], false);
    client.set_web_search(false);
    client.chat_collaborative(&history()).await.unwrap();
    let request = requests.lock().unwrap()[1].clone();
    assert!(request.get("tools").is_none());
    assert!(request.get("messages").is_some());
    for status in ["incomplete", "in_progress", "failed"] {
        let mut response = response.clone();
        response["status"] = status.into();
        assert!(
            LLMClient::parse_openai_search(&response)
                .map(|(_, complete)| !complete)
                .unwrap_or(true)
        );
    }
}

#[tokio::test]
async fn claude_resumes_paused_server_tools_preserving_opaque_blocks_and_citations() {
    for model in ClaudeModel::ALL {
        let paused = json!({"type":"message","stop_reason":"pause_turn","content":[{"type":"server_tool_use","id":"search-1","name":"web_search","input":{"query":"新闻"}},{"type":"web_search_tool_result","tool_use_id":"search-1","content":[{"type":"web_search_result","url":"https://example.org/news","title":"新闻","encrypted_content":"opaque"}]}]});
        let done = json!({"type":"message","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"新闻事实","citations":[{"type":"web_search_result_location","url":"https://example.org/news","title":"新闻","encrypted_index":"opaque-index"}]}]});
        let mut configured = client(ModelProvider::Claude);
        configured.model = model.api_id().into();
        let (client, requests) = fixtures(configured, vec![paused.clone(), done]);
        let (text, complete) = client.chat_collaborative(&history()).await.unwrap();
        assert!(
            complete
                && text.contains("[新闻](<https://example.org/news>)")
                && !text.contains("opaque")
                && !text.contains("private")
        );
        let requests = requests.lock().unwrap();
        assert_eq!(requests.len(), 2);
        assert_eq!(requests[0]["model"], model.api_id());
        assert_eq!(requests[0]["tools"][0]["type"], "web_search_20250305");
        assert_eq!(requests[1]["messages"][3]["content"], paused["content"]);
    }
    let error = json!({"content":[{"type":"text","text":"回答"},{"type":"web_search_tool_result","content":{"type":"web_search_tool_result_error","error_code":"unavailable"}}],"stop_reason":"end_turn"});
    assert!(LLMClient::parse_claude_search(&error).is_err());
}

#[test]
fn results_are_bounded_deduplicated_and_empty_results_are_honest() {
    let mut sources = Sources::default();
    let data=LLMClient::deepseek_results(&json!({"results":[{"url":"javascript:bad","title":"bad"},{"url":"https://example.org","title":"a","summary":"界".repeat(5000)},{"url":"https://example.org","title":"duplicate"}]}),&mut sources).unwrap();
    assert_eq!(sources.0.len(), 1);
    assert_eq!(
        data["results"][0]["summary"]
            .as_str()
            .unwrap()
            .chars()
            .count(),
        4000
    );
    let mut text = "answer".to_string();
    Sources::default().append(&mut text);
    assert!(text.contains("未返回可核验"));
    for provider in [
        ModelProvider::DeepSeek,
        ModelProvider::OpenAI,
        ModelProvider::Claude,
    ] {
        let mut client = client(provider);
        client.set_web_search(true);
        assert!(client.web_search_enabled());
        let request = client.build_limited_request(&history(), 237).unwrap();
        assert!(request.get("tools").is_none());
    }
}

#[tokio::test]
async fn single_model_chat_uses_search_dispatch_for_every_new_provider() {
    for provider in [
        ModelProvider::DeepSeek,
        ModelProvider::OpenAI,
        ModelProvider::Claude,
    ] {
        let requests = Arc::new(Mutex::new(Vec::new()));
        let client = client(provider).test_transport(requests.clone(), false);
        let result = client
            .chat(&history(), &std::collections::HashMap::new())
            .await
            .unwrap();
        assert!(
            matches!(result, crate::llm::LLMResponse::Text(text) if text.contains("未返回可核验"))
        );
        let request = requests.lock().unwrap()[0].clone();
        assert!(request.get("tools").is_some());
    }
}
