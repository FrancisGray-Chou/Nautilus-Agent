use dotenvy::dotenv;
use nautilus_agent::{
    NautilusAgent,
    config::{GeminiMode, ModelProvider},
};
use std::io::{self, Write};

fn print_menu() {
    println!("请选择服务：1 = DeepSeek，2 = OpenAI，3 = Gemini，4 = Claude");
}

fn print_gemini_menu() {
    println!("Gemini 模式：");
    println!("1 = 3.8 Flash（Extended Thinking + Search）");
    println!("2 = 4 Argon（思考 + Search）");
    println!("3 = 自动（普通任务用 Flash，复杂任务用 Argon，均启用搜索工具）");
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum InputMode {
    Provider,
    Gemini,
    Chat,
}

#[derive(Debug, PartialEq)]
enum InputAction<'a> {
    Ignore,
    Exit,
    ShowModels,
    SelectModel(ModelProvider),
    SelectGemini(GeminiMode),
    InvalidSelection,
    Task(&'a str),
}

fn parse_input(input: &str, mode: InputMode) -> InputAction<'_> {
    let input = input.trim();
    match input {
        "" => InputAction::Ignore,
        "/exit" | "/quit" => InputAction::Exit,
        "/model" => InputAction::ShowModels,
        _ => match mode {
            InputMode::Provider => ModelProvider::from_number(input)
                .map(InputAction::SelectModel)
                .unwrap_or(InputAction::InvalidSelection),
            InputMode::Gemini => GeminiMode::from_number(input)
                .map(InputAction::SelectGemini)
                .unwrap_or(InputAction::InvalidSelection),
            InputMode::Chat => InputAction::Task(input),
        },
    }
}

fn select_model(
    agent: &mut Option<NautilusAgent>,
    provider: ModelProvider,
    gemini_mode: Option<GeminiMode>,
) -> Result<(), String> {
    if let Some(current) = agent.as_mut() {
        match gemini_mode {
            Some(mode) => current.switch_gemini_mode(mode)?,
            None => current.switch_provider(provider)?,
        }
    } else {
        *agent = Some(match gemini_mode {
            Some(mode) => NautilusAgent::with_gemini_mode(mode)?,
            None => NautilusAgent::with_provider(provider)?,
        });
    }
    if let Some(current) = agent.as_ref() {
        if let Some(mode) = current.gemini_mode() {
            println!("当前模型：Gemini / {}", mode.name());
        } else {
            println!(
                "当前模型：{} / {}",
                current.provider().name(),
                current.model()
            );
        }
        if current.provider() == ModelProvider::Gemini {
            println!(
                "Google Search：{}",
                if current.web_search_enabled() {
                    "已启用（按需搜索）"
                } else {
                    "未启用"
                }
            );
        }
    }
    Ok(())
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    dotenv().ok();
    println!(">>> 启动 Nautilus-Agent...");
    print_menu();
    println!("每次任务完成后可继续输入，/model 切换模型，/exit 或 /quit 退出。");
    let mut agent: Option<NautilusAgent> = None;
    let mut mode = InputMode::Provider;

    loop {
        print!(
            "{}",
            match mode {
                InputMode::Provider => "选择服务 [1/2/3/4]: ",
                InputMode::Gemini => "选择 Gemini 模式 [1/2/3]: ",
                InputMode::Chat => "User: ",
            }
        );
        io::stdout().flush()?;
        let mut input = String::new();
        if io::stdin().read_line(&mut input)? == 0 {
            break;
        }
        match parse_input(&input, mode) {
            InputAction::Ignore => continue,
            InputAction::Exit => break,
            InputAction::ShowModels => {
                mode = InputMode::Provider;
                print_menu();
            }
            InputAction::InvalidSelection => println!("请输入当前菜单列出的数字选择选项。"),
            InputAction::SelectModel(ModelProvider::Gemini) => {
                mode = InputMode::Gemini;
                print_gemini_menu();
            }
            InputAction::SelectModel(provider) => match select_model(&mut agent, provider, None) {
                Ok(()) => mode = InputMode::Chat,
                Err(error) => eprintln!("切换失败：{error}，请重新选择模型。"),
            },
            InputAction::SelectGemini(gemini_mode) => {
                match select_model(&mut agent, ModelProvider::Gemini, Some(gemini_mode)) {
                    Ok(()) => mode = InputMode::Chat,
                    Err(error) => eprintln!("切换失败：{error}，请重新选择模型。"),
                }
            }
            InputAction::Task(user_msg) => {
                if let Some(current) = agent.as_mut() {
                    if current.gemini_mode() == Some(GeminiMode::Auto) {
                        println!("自动选择：{}", current.model_for_task(user_msg));
                    }
                    println!("(Nautilus 正在思考...)");
                    match current.run(user_msg).await {
                        Ok(response) => println!("Nautilus: {response}"),
                        Err(error) => {
                            eprintln!("任务失败：{error}。可继续输入任务，或使用 /model 切换模型。")
                        }
                    }
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_are_tasks_outside_the_model_menu() {
        for input in ["1", "2", "3", "继续处理任务"] {
            assert_eq!(
                parse_input(input, InputMode::Chat),
                InputAction::Task(input)
            );
        }
    }

    #[test]
    fn numbers_select_providers_in_the_top_menu() {
        for (input, provider) in [
            ("1", ModelProvider::DeepSeek),
            ("2", ModelProvider::OpenAI),
            ("3", ModelProvider::Gemini),
            ("4", ModelProvider::Claude),
        ] {
            assert_eq!(
                parse_input(input, InputMode::Provider),
                InputAction::SelectModel(provider)
            );
        }
        assert_eq!(
            parse_input("继续处理任务", InputMode::Provider),
            InputAction::InvalidSelection
        );
    }

    #[test]
    fn gemini_menu_has_its_own_numeric_choices() {
        for (input, mode) in [
            ("1", GeminiMode::FlashSearch),
            ("2", GeminiMode::Argon),
            ("3", GeminiMode::Auto),
        ] {
            assert_eq!(
                parse_input(input, InputMode::Gemini),
                InputAction::SelectGemini(mode)
            );
        }
        assert_eq!(
            parse_input("4", InputMode::Gemini),
            InputAction::InvalidSelection
        );
    }

    #[test]
    fn commands_work_in_all_modes() {
        for mode in [InputMode::Provider, InputMode::Gemini, InputMode::Chat] {
            assert_eq!(parse_input(" /model ", mode), InputAction::ShowModels);
            assert_eq!(parse_input("/exit", mode), InputAction::Exit);
            assert_eq!(parse_input("/quit", mode), InputAction::Exit);
            assert_eq!(parse_input(" \n", mode), InputAction::Ignore);
        }
    }
}
