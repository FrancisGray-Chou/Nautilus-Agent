//! 当前操作系统用户的明文 .env。密钥不写入工程目录、不导出至进程环境。

use crate::config::ModelProvider;
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};

pub const NOTICE_VERSION: &str = "local-plaintext-v1";
const KEYS: [&str; 3] = ["DEEPSEEK_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY"];
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

pub struct CredentialStore {
    directory: PathBuf,
    keys: BTreeMap<String, String>,
}

impl CredentialStore {
    pub fn current_user() -> Result<Self, String> {
        let directory = if let Some(path) = std::env::var_os("NAUTILUS_PROFILE_DIR") {
            PathBuf::from(path)
        } else {
            let home = PathBuf::from(std::env::var_os("HOME").ok_or("无法确定当前用户目录")?);
            if cfg!(target_os = "macos") {
                home.join("Library/Application Support/Nautilus")
            } else {
                std::env::var_os("XDG_CONFIG_HOME")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| home.join(".config"))
                    .join("Nautilus")
            }
        };
        Self::open(directory)
    }

    pub fn open(directory: PathBuf) -> Result<Self, String> {
        let mut store = Self {
            directory,
            keys: BTreeMap::new(),
        };
        if store.directory.exists() {
            store.check_directory()?;
            let file = store.directory.join(".env");
            if fs::symlink_metadata(&file).is_ok() {
                private_path(&file, false)?;
                if fs::metadata(&file)
                    .map_err(|_| "无法读取密钥文件属性")?
                    .len()
                    > 65536
                {
                    return Err("密钥配置文件过大".into());
                }
                let data = fs::read(&file).map_err(|_| "无法读取当前用户的密钥配置")?;
                for entry in dotenvy::from_read_iter(data.as_slice()) {
                    let (name, key) =
                        entry.map_err(|_| "密钥配置文件格式无效，请在设置中重新配置")?;
                    if KEYS.contains(&name.as_str()) && !key.is_empty() {
                        validate_key(&key)?;
                        store.keys.insert(name, key);
                    }
                }
            }
        }
        Ok(store)
    }

    fn check_directory(&self) -> Result<(), String> {
        private_path(&self.directory, true)
    }

    pub fn key(&self, provider: ModelProvider) -> Result<&str, String> {
        let name = match provider {
            ModelProvider::DeepSeek => KEYS[0],
            ModelProvider::OpenAI => KEYS[1],
            ModelProvider::Gemini => KEYS[2],
        };
        self.keys.get(name).map(String::as_str).ok_or_else(|| {
            format!(
                "请在「API 密钥与隐私」中填写 {} 的 API key",
                provider.name()
            )
        })
    }

    pub fn status(&self) -> Value {
        json!({"storage_path": self.directory.join(".env").to_string_lossy(),
            "account": std::env::var("USER").unwrap_or_else(|_| "当前操作系统用户".into()),
            "deepseek": self.keys.contains_key(KEYS[0]), "openai": self.keys.contains_key(KEYS[1]), "gemini": self.keys.contains_key(KEYS[2]),
            "notice_version": NOTICE_VERSION, "storage": "plaintext"})
    }

    /// None 保持原值；空字符串删除该平台密钥。先校验全部更新，再原子写入。
    pub fn save(&mut self, updates: [Option<String>; 3], acknowledged: bool) -> Result<(), String> {
        if !acknowledged {
            return Err("保存前请确认已阅读明文存储和第三方调用说明".into());
        }
        let mut next = self.keys.clone();
        for (name, value) in KEYS.into_iter().zip(updates) {
            if let Some(value) = value {
                let key = value.trim();
                if key.is_empty() {
                    next.remove(name);
                } else {
                    validate_key(key)?;
                    next.insert(name.to_string(), key.to_string());
                }
            }
        }
        self.persist(&next)?;
        self.keys = next;
        Ok(())
    }

    pub fn delete(&mut self, provider: &str) -> Result<(), String> {
        let mut next = self.keys.clone();
        match provider {
            "all" => next.clear(),
            "deepseek" => {
                next.remove(KEYS[0]);
            }
            "openai" => {
                next.remove(KEYS[1]);
            }
            "gemini" => {
                next.remove(KEYS[2]);
            }
            _ => return Err("未知平台".into()),
        }
        self.persist(&next)?;
        self.keys = next;
        Ok(())
    }

    fn persist(&self, keys: &BTreeMap<String, String>) -> Result<(), String> {
        #[cfg(unix)]
        {
            use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
            fs::DirBuilder::new()
                .recursive(true)
                .mode(0o700)
                .create(&self.directory)
                .map_err(|_| "无法创建私有配置目录")?;
            self.check_directory()?;
            let destination = self.directory.join(".env");
            if fs::symlink_metadata(&destination).is_ok() {
                private_path(&destination, false)?;
            }
            if keys.is_empty() {
                match fs::remove_file(&destination) {
                    Ok(()) => {}
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                    Err(_) => return Err("无法删除密钥配置文件".into()),
                }
                return Ok(());
            }
            let temporary = self.directory.join(format!(
                ".env.tmp.{}.{}",
                std::process::id(),
                TEMP_ID.fetch_add(1, Ordering::Relaxed)
            ));
            let result = (|| {
                let mut file = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .mode(0o600)
                    .open(&temporary)
                    .map_err(|_| "无法创建私有密钥文件")?;
                writeln!(file, "# Nautilus local plaintext credentials; delete in API settings\nPRIVACY_NOTICE_VERSION='{NOTICE_VERSION}'").map_err(|_| "写入密钥配置失败")?;
                for (name, value) in keys {
                    writeln!(file, "{name}='{value}'").map_err(|_| "写入密钥配置失败")?;
                }
                file.sync_all().map_err(|_| "保存密钥配置失败")?;
                fs::rename(&temporary, &destination).map_err(|_| "替换密钥配置失败")?;
                Ok(())
            })();
            if result.is_err() {
                let _ = fs::remove_file(&temporary);
            }
            result
        }
        #[cfg(not(unix))]
        {
            let _ = keys;
            Err("此版本的明文密钥存储仅支持 macOS/Linux；其他系统需先实现账户 ACL".into())
        }
    }

    pub fn redact(&self, text: &str) -> String {
        self.keys.values().fold(text.to_string(), |message, key| {
            message.replace(key, "[REDACTED]")
        })
    }
}

fn validate_key(key: &str) -> Result<(), String> {
    if key.len() > 4096
        || key.is_empty()
        || !key.bytes().all(|b| (33..=126).contains(&b) && b != b'\'')
    {
        return Err("API key 必须为单行 ASCII 字符，不得包含空白、控制字符或单引号".into());
    }
    Ok(())
}

#[cfg(unix)]
fn private_path(path: &Path, directory: bool) -> Result<(), String> {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    let metadata = fs::symlink_metadata(path).map_err(|_| "无法读取私有配置路径")?;
    // geteuid is a read-only OS call and does not mutate process state.
    let owner = unsafe { libc::geteuid() };
    if metadata.file_type().is_symlink()
        || metadata.uid() != owner
        || (directory && !metadata.is_dir())
        || (!directory && (!metadata.is_file() || metadata.nlink() != 1))
    {
        return Err("密钥配置必须是当前系统用户拥有的普通文件和私有目录，不允许链接".into());
    }
    fs::set_permissions(
        path,
        fs::Permissions::from_mode(if directory { 0o700 } else { 0o600 }),
    )
    .map_err(|_| "无法限制密钥配置权限".to_string())
}

#[cfg(not(unix))]
fn private_path(_path: &Path, _directory: bool) -> Result<(), String> {
    Err("此系统尚未实现账户 ACL".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn temporary() -> PathBuf {
        std::env::temp_dir().join(format!(
            "nautilus-keys-{}-{}",
            std::process::id(),
            TEMP_ID.fetch_add(1, Ordering::Relaxed)
        ))
    }

    #[test]
    fn plaintext_roundtrip_is_private_and_does_not_echo_keys() {
        use std::os::unix::fs::PermissionsExt;
        let directory = temporary();
        let mut store = CredentialStore::open(directory.clone()).unwrap();
        assert!(
            store
                .save([Some("test-key-$literal#suffix".into()), None, None], false)
                .is_err()
        );
        assert!(!directory.exists());
        store
            .save(
                [
                    Some("test-key-$literal#suffix".into()),
                    None,
                    Some("gemini-test".into()),
                ],
                true,
            )
            .unwrap();
        assert_eq!(
            fs::metadata(directory.join(".env"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        assert_eq!(
            fs::metadata(&directory).unwrap().permissions().mode() & 0o777,
            0o700
        );
        assert!(
            fs::read_to_string(directory.join(".env"))
                .unwrap()
                .contains("test-key-$literal#suffix")
        );
        let loaded = CredentialStore::open(directory.clone()).unwrap();
        assert_eq!(
            loaded.key(ModelProvider::DeepSeek).unwrap(),
            "test-key-$literal#suffix"
        );
        assert!(!loaded.status().to_string().contains("gemini-test"));
        assert_eq!(loaded.redact("error gemini-test"), "error [REDACTED]");
        store.delete("deepseek").unwrap();
        assert!(store.key(ModelProvider::DeepSeek).is_err());
        assert!(store.key(ModelProvider::Gemini).is_ok());
        store.delete("all").unwrap();
        assert!(!directory.join(".env").exists());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn invalid_keys_do_not_change_saved_credentials_and_accounts_are_isolated() {
        let first = temporary();
        let second = temporary();
        let mut a = CredentialStore::open(first.clone()).unwrap();
        a.save([Some("account-a".into()), None, None], true)
            .unwrap();
        assert!(
            a.save(
                [Some("bad\nGEMINI_API_KEY=inject".into()), None, None],
                true
            )
            .is_err()
        );
        assert_eq!(a.key(ModelProvider::DeepSeek).unwrap(), "account-a");
        let b = CredentialStore::open(second).unwrap();
        assert!(b.key(ModelProvider::DeepSeek).is_err());
        fs::remove_dir_all(first).unwrap();
    }

    #[test]
    fn symlink_store_is_rejected() {
        use std::os::unix::fs::symlink;
        let base = temporary();
        fs::create_dir(&base).unwrap();
        let target = base.join("target");
        fs::write(&target, "secret").unwrap();
        let profile = base.join("profile");
        fs::create_dir(&profile).unwrap();
        symlink(&target, profile.join(".env")).unwrap();
        assert!(CredentialStore::open(profile).is_err());
        fs::remove_dir_all(base).unwrap();
    }
}
