//! Reserved for a future budget mode; the Mixture test release does not call this module.
use crate::message::Message;
const MIN_OUTPUT: u64 = 200;
#[derive(Clone, Copy)]
pub struct Price {
    pub input: u64,
    pub output: u64,
}
pub fn cap(budget: u64, input: u64, price: Price, ceiling: u64) -> Option<(u32, u64)> {
    let input_cost = input.checked_mul(price.input)?;
    let tokens = budget
        .checked_sub(input_cost)?
        .checked_div(price.output)?
        .min(ceiling)
        .min(u64::from(u32::MAX));
    if tokens < MIN_OUTPUT {
        return None;
    }
    Some((
        tokens as u32,
        input_cost.checked_add(tokens.checked_mul(price.output)?)?,
    ))
}

/// Deliberately overestimate text: UTF-8 bytes plus framing reserve per message.
/// This is not a provider-authoritative tokenizer or a billing guarantee.
pub fn input_bound(history: &[Message]) -> u64 {
    history
        .iter()
        .map(|m| match m {
            Message::User(s) | Message::Assistant(s) => s.len() as u64 + 128,
            Message::ToolResult(_, _) => 128,
        })
        .sum::<u64>()
        .saturating_add(1024)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_budget_and_threshold() {
        let p = Price {
            input: 2,
            output: 10,
        };
        assert_eq!(cap(2200, 100, p, 4096), Some((200, 2200)));
        assert_eq!(cap(2199, 100, p, 4096), None);
        assert_eq!(cap(199, 100, p, 4096), None);
        assert_eq!(cap(u64::MAX, u64::MAX, p, 4096), None);
        assert_eq!(cap(10000, 100, p, 512), Some((512, 5320)));
    }
    #[test]
    fn reservations_never_exceed_budget() {
        for budget in 0..20000 {
            let p = Price {
                input: 3,
                output: 7,
            };
            if let Some((n, cost)) = cap(budget, 100, p, 4096) {
                assert!(n >= 200);
                assert!(cost <= budget);
                assert_eq!(cost, 300 + u64::from(n) * 7);
            }
        }
    }
    #[test]
    fn unicode_and_history_are_counted() {
        assert!(input_bound(&[Message::user("你好")]) > input_bound(&[Message::user("hi")]));
        assert!(
            input_bound(&[Message::user("hi"), Message::assistant("ok")])
                > input_bound(&[Message::user("hi")])
        );
    }
}
