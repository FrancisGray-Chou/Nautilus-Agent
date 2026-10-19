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

#[cfg(any(windows, test))]
mod windows;

pub const NOTICE_VERSION: &str = "local-plaintext-v1";
const KEYS: [&str; 4] = [
    "DEEPSEEK_API_KEY",
    "OPENAI_API_KEY",
    "GEMINI_API_KEY",
    "ANTHROPIC_API_KEY",
];
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

pub struct CredentialStore {
    directory: PathBuf,
    keys: BTreeMap<String, String>,
}

impl CredentialStore {
    pub fn current_user() -> Result<Self, String> {
        let directory = profile_directory(std::env::consts::OS, |name| std::env::var_os(name))?;
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
            ModelProvider::Claude => KEYS[3],
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
            "account": std::env::var(if cfg!(windows) { "USERNAME" } else { "USER" }).unwrap_or_else(|_| "当前操作系统用户".into()),
            "deepseek": self.keys.contains_key(KEYS[0]), "openai": self.keys.contains_key(KEYS[1]), "gemini": self.keys.contains_key(KEYS[2]),
            "claude": self.keys.contains_key(KEYS[3]),
            "notice_version": NOTICE_VERSION, "storage": "plaintext"})
    }

    /// None 保持原值；空字符串删除该平台密钥。先校验全部更新，再原子写入。
    pub fn save(&mut self, updates: [Option<String>; 4], acknowledged: bool) -> Result<(), String> {
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
            "claude" => {
                next.remove(KEYS[3]);
            }
            _ => return Err("未知平台".into()),
        }
        self.persist(&next)?;
        self.keys = next;
        Ok(())
    }

    fn persist(&self, keys: &BTreeMap<String, String>) -> Result<(), String> {
        create_private_directory(&self.directory)?;
        let destination = self.directory.join(".env");
        if fs::symlink_metadata(&destination).is_ok() {
            private_path(&destination, false)?;
        }
        if keys.is_empty() {
            return match fs::remove_file(&destination) {
                Ok(()) => Ok(()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
                Err(_) => Err("无法删除密钥配置文件".into()),
            };
        }
        let temporary = self.directory.join(format!(
            ".env.tmp.{}.{}",
            std::process::id(),
            TEMP_ID.fetch_add(1, Ordering::Relaxed)
        ));
        let result = (|| {
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            // Windows inherits the already protected parent DACL, then gets its own
            // protected DACL before any key bytes are written.
            let file = options
                .open(&temporary)
                .map_err(|_| "无法创建私有密钥文件")?;
            #[cfg(windows)]
            drop(file);
            #[cfg(not(windows))]
            let mut file = file;
            private_path(&temporary, false)?;
            #[cfg(windows)]
            let mut file = OpenOptions::new()
                .write(true)
                .open(&temporary)
                .map_err(|_| "无法打开私有密钥文件")?;
            writeln!(file, "# Nautilus local plaintext credentials; delete in API settings\nPRIVACY_NOTICE_VERSION='{NOTICE_VERSION}'").map_err(|_| "写入密钥配置失败")?;
            for (name, value) in keys {
                writeln!(file, "{name}='{value}'").map_err(|_| "写入密钥配置失败")?;
            }
            file.sync_all().map_err(|_| "保存密钥配置失败")?;
            drop(file); // Windows must not retain a writer while replacing the destination.
            fs::rename(&temporary, &destination).map_err(|_| "替换密钥配置失败")?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
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

#[cfg(windows)]
fn private_path(path: &Path, directory: bool) -> Result<(), String> {
    windows::private_path(path, directory)
}
#[cfg(not(any(unix, windows)))]
fn private_path(_path: &Path, _directory: bool) -> Result<(), String> {
    Err("此系统尚未实现账户权限".into())
}

fn create_private_directory(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(path)
            .map_err(|_| "无法创建私有配置目录")?;
    }
    #[cfg(not(unix))]
    fs::create_dir_all(path).map_err(|_| "无法创建私有配置目录")?;
    private_path(path, true)
}

fn profile_directory(
    os: &str,
    lookup: impl Fn(&str) -> Option<std::ffi::OsString>,
) -> Result<PathBuf, String> {
    if let Some(path) = lookup("NAUTILUS_PROFILE_DIR").filter(|value| !value.is_empty()) {
        return Ok(PathBuf::from(path));
    }
    if os == "windows" {
        return lookup("APPDATA")
            .filter(|value| !value.is_empty())
            .map(|path| PathBuf::from(path).join("Nautilus"))
            .or_else(|| {
                lookup("USERPROFILE")
                    .filter(|value| !value.is_empty())
                    .map(|path| PathBuf::from(path).join("AppData/Roaming/Nautilus"))
            })
            .ok_or_else(|| "无法确定 Windows 当前用户配置目录".into());
    }
    let home = PathBuf::from(lookup("HOME").ok_or("无法确定当前用户目录")?);
    Ok(if os == "macos" {
        home.join("Library/Application Support/Nautilus")
    } else {
        lookup("XDG_CONFIG_HOME")
            .filter(|value| !value.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".config"))
            .join("Nautilus")
    })
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
    fn profile_paths_are_consistent_and_windows_does_not_require_home() {
        let lookup = |name: &str| match name {
            "APPDATA" => Some("C:/Users/用户/AppData/Roaming".into()),
            "USERPROFILE" => Some("C:/Users/用户".into()),
            _ => None,
        };
        assert_eq!(
            profile_directory("windows", lookup).unwrap(),
            PathBuf::from("C:/Users/用户/AppData/Roaming/Nautilus")
        );
        assert_eq!(
            profile_directory("windows", |name| (name == "USERPROFILE")
                .then(|| "C:/Users/Francis Gray".into()))
            .unwrap(),
            PathBuf::from("C:/Users/Francis Gray/AppData/Roaming/Nautilus")
        );
        assert_eq!(
            profile_directory("windows", |name| (name == "NAUTILUS_PROFILE_DIR")
                .then(|| "custom-profile".into()))
            .unwrap(),
            PathBuf::from("custom-profile")
        );
        assert!(profile_directory("windows", |_| None).is_err());
    }

    #[test]
    fn hard_link_credentials_are_rejected() {
        let directory = temporary();
        fs::create_dir(&directory).unwrap();
        let target = directory.join("target");
        fs::write(&target, "secret").unwrap();
        fs::hard_link(&target, directory.join(".env")).unwrap();
        assert!(CredentialStore::open(directory.clone()).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn plaintext_roundtrip_is_private_and_does_not_echo_keys() {
        #[cfg(unix)]
        use std::os::unix::fs::PermissionsExt;
        let directory = temporary();
        let mut store = CredentialStore::open(directory.clone()).unwrap();
        assert!(
            store
                .save(
                    [Some("test-key-$literal#suffix".into()), None, None, None],
                    false
                )
                .is_err()
        );
        assert!(!directory.exists());
        store
            .save(
                [
                    Some("test-key-$literal#suffix".into()),
                    None,
                    Some("gemini-test".into()),
                    Some("claude-test".into()),
                ],
                true,
            )
            .unwrap();
        #[cfg(unix)]
        {
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
        }
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
        assert_eq!(loaded.key(ModelProvider::Claude).unwrap(), "claude-test");
        assert_eq!(loaded.status()["claude"], true);
        assert_eq!(loaded.redact("claude-test"), "[REDACTED]");
        assert!(!loaded.status().to_string().contains("gemini-test"));
        assert_eq!(loaded.redact("error gemini-test"), "error [REDACTED]");
        store.delete("deepseek").unwrap();
        assert!(store.key(ModelProvider::DeepSeek).is_err());
        assert!(store.key(ModelProvider::Gemini).is_ok());
        store.delete("claude").unwrap();
        assert!(store.key(ModelProvider::Claude).is_err());
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
        a.save([Some("account-a".into()), None, None, None], true)
            .unwrap();
        assert!(
            a.save(
                [Some("bad\nGEMINI_API_KEY=inject".into()), None, None, None],
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
    #[cfg(unix)]
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
