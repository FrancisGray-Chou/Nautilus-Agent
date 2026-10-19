use super::*;
use serde_json::json;

#[test]
fn deepseek_request_preserves_model_and_conversation() {
    let client = LLMClient::from_config(LLMConfig {
        provider: ModelProvider::DeepSeek,
        api_key: "test-deepseek-key".into(),
        model: "deepseek-v4-pro".into(),
        web_search: false,
    })
    .unwrap();
    assert_eq!(
        client.config.url("/chat/completions"),
        "https://api.deepseek.com/chat/completions"
    );
    assert_eq!(
        client.config.headers()["authorization"],
        "Bearer test-deepseek-key"
    );
    let history = [
        Message::user("Hello"),
        Message::assistant("Hi"),
        Message::user("你好"),
    ];
    let request = serde_json::to_value(client.build_request(&history).unwrap()).unwrap();
    assert_eq!(request["model"], "deepseek-v4-pro");
    assert_eq!(
        request["messages"],
        json!([
            {"role": "user", "content": "Hello"}, {"role": "assistant", "content": "Hi"},
            {"role": "user", "content": "你好"}
        ])
    );
}

fn completion(choices: Value) -> CreateChatCompletionResponse {
    serde_json::from_value(
        json!({"id": "test", "object": "chat.completion", "created": 0,
        "model": "deepseek-flash", "choices": choices}),
    )
    .unwrap()
}

#[test]
fn deepseek_reply_accepts_extra_reasoning_fields() {
    let response = completion(json!([{
        "index": 0, "message": {"role": "assistant", "content": "你好", "reasoning_content": "思考过程"},
        "finish_reason": "stop"
    }]));
    assert_eq!(
        LLMClient::parse_response(response),
        Ok(LLMResponse::Text("你好".into()))
    );
}

#[test]
fn empty_responses_are_errors() {
    assert!(LLMClient::parse_response(completion(json!([]))).is_err());
    for content in [Value::Null, json!(""), json!("  ")] {
        let response = completion(json!([{
            "index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"
        }]));
        assert!(LLMClient::parse_response(response).is_err());
    }
}

#[test]
fn provider_requests_use_the_correct_endpoint_and_gemini_thinking() {
    for (provider, endpoint) in [
        (
            ModelProvider::DeepSeek,
            "https://api.deepseek.com/chat/completions",
        ),
        (
            ModelProvider::OpenAI,
            "https://api.openai.com/v1/chat/completions",
        ),
        (
            ModelProvider::Gemini,
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        ),
    ] {
        let client = LLMClient::from_config(LLMConfig {
            provider,
            api_key: "test-key".into(),
            model: "test-model".into(),
            web_search: false,
        })
        .unwrap();
        assert_eq!(client.config.url("/chat/completions"), endpoint);
        let request = client.build_request(&[Message::user("你好")]).unwrap();
        assert_eq!(request["model"], "test-model");
        if provider == ModelProvider::Gemini {
            assert_eq!(request["reasoning_effort"], "high");
        } else {
            assert!(request.get("reasoning_effort").is_none());
        }
    }
}

#[test]
fn budget_limits_are_serialized_for_every_provider() {
    for provider in [
        ModelProvider::DeepSeek,
        ModelProvider::OpenAI,
        ModelProvider::Gemini,
    ] {
        let client = LLMClient::from_config(LLMConfig {
            provider,
            api_key: "test".into(),
            model: "test".into(),
            web_search: false,
        })
        .unwrap();
        let request = client
            .build_limited_request(&[Message::user("task")], 237)
            .unwrap();
        let key = if provider == ModelProvider::OpenAI {
            "max_completion_tokens"
        } else {
            "max_tokens"
        };
        assert_eq!(request[key], 237);
        assert!(client.build_limited_request(&[], 199).is_err());
        assert!(request.get("tools").is_none_or(Value::is_null));
    }
}
