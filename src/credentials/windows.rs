//! Minimal Win32 ACL bindings; no shell, PowerShell, administrator prompt or extra dependency.
#![cfg_attr(not(windows), allow(dead_code))]
#[cfg(windows)]
use std::os::windows::ffi::OsStrExt;
use std::{
    ffi::c_void,
    path::Path,
    ptr::{null, null_mut},
};

type Handle = *mut c_void;
type Sid = *mut c_void;
const READ_CONTROL: u32 = 0x0002_0000;
const WRITE_DAC: u32 = 0x0004_0000;
const WRITE_OWNER: u32 = 0x0008_0000;
const OWNER_SECURITY_INFORMATION: u32 = 1;
const DACL_SECURITY_INFORMATION: u32 = 4;
const PROTECTED_DACL_SECURITY_INFORMATION: u32 = 0x8000_0000;
const FILE_ATTRIBUTE_DIRECTORY: u32 = 0x10;
const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x400;

#[repr(C)]
#[derive(Default)]
struct FileTime {
    low: u32,
    high: u32,
}
#[repr(C)]
#[derive(Default)]
struct FileInformation {
    attributes: u32,
    creation: FileTime,
    access: FileTime,
    write: FileTime,
    volume: u32,
    size_high: u32,
    size_low: u32,
    links: u32,
    index_high: u32,
    index_low: u32,
}
#[repr(C)]
struct TokenUser {
    sid: Sid,
    attributes: u32,
}

#[cfg_attr(windows, link(name = "kernel32"))]
unsafe extern "system" {
    fn GetCurrentProcess() -> Handle;
    fn CloseHandle(handle: Handle) -> i32;
    fn LocalFree(memory: *mut c_void) -> *mut c_void;
    fn CreateFileW(
        name: *const u16,
        access: u32,
        sharing: u32,
        security: *const c_void,
        creation: u32,
        flags: u32,
        template: Handle,
    ) -> Handle;
    fn GetFileInformationByHandle(handle: Handle, information: *mut FileInformation) -> i32;
}
#[cfg_attr(windows, link(name = "advapi32"))]
unsafe extern "system" {
    fn OpenProcessToken(process: Handle, access: u32, token: *mut Handle) -> i32;
    fn GetTokenInformation(
        token: Handle,
        class: i32,
        data: *mut c_void,
        length: u32,
        needed: *mut u32,
    ) -> i32;
    fn EqualSid(first: Sid, second: Sid) -> i32;
    fn ConvertSidToStringSidW(sid: Sid, text: *mut *mut u16) -> i32;
    fn ConvertStringSecurityDescriptorToSecurityDescriptorW(
        text: *const u16,
        revision: u32,
        descriptor: *mut *mut c_void,
        size: *mut u32,
    ) -> i32;
    fn GetSecurityDescriptorDacl(
        descriptor: *const c_void,
        present: *mut i32,
        dacl: *mut *mut c_void,
        defaulted: *mut i32,
    ) -> i32;
    fn GetSecurityInfo(
        object: Handle,
        kind: i32,
        information: u32,
        owner: *mut Sid,
        group: *mut Sid,
        dacl: *mut *mut c_void,
        sacl: *mut *mut c_void,
        descriptor: *mut *mut c_void,
    ) -> u32;
    fn SetSecurityInfo(
        object: Handle,
        kind: i32,
        information: u32,
        owner: Sid,
        group: Sid,
        dacl: *mut c_void,
        sacl: *mut c_void,
    ) -> u32;
}

struct OwnedHandle(Handle);
impl Drop for OwnedHandle {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}
struct LocalMemory(*mut c_void);
impl Drop for LocalMemory {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.0);
        }
    }
}
fn wide_path(path: &Path) -> Result<Vec<u16>, String> {
    #[cfg(windows)]
    let mut result: Vec<u16> = path.as_os_str().encode_wide().collect();
    #[cfg(not(windows))]
    let mut result: Vec<u16> = path.to_string_lossy().encode_utf16().collect();
    if result.contains(&0) {
        return Err("配置路径不能含有空字符".into());
    }
    result.push(0);
    Ok(result)
}

/// Open the object itself (never follow a reparse point), verify owner/link count,
/// and replace its DACL with a protected full-control ACE for the current user.
pub(super) fn private_path(path: &Path, directory: bool) -> Result<(), String> {
    let path = wide_path(path)?;
    // SAFETY: every pointer refers to a live, correctly aligned buffer; all returned
    // handles and LocalAlloc buffers have one RAII owner and outlive their SID/ACL views.
    unsafe {
        let object = CreateFileW(
            path.as_ptr(),
            READ_CONTROL | WRITE_DAC | WRITE_OWNER,
            3,
            null(),
            3,
            0x0020_0000 | 0x0200_0000,
            null_mut(),
        ); // OPEN_REPARSE_POINT | BACKUP_SEMANTICS
        if object as isize == -1 {
            return Err("无法打开 Windows 私有配置路径，请检查当前账户权限".into());
        }
        let object = OwnedHandle(object);
        let mut info = FileInformation::default();
        if GetFileInformationByHandle(object.0, &mut info) == 0
            || info.attributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
            || (info.attributes & FILE_ATTRIBUTE_DIRECTORY != 0) != directory
            || (!directory && info.links != 1)
        {
            return Err("配置必须是普通文件和目录，不能使用重解析点、符号链接或硬链接".into());
        }
        let mut token = null_mut();
        if OpenProcessToken(GetCurrentProcess(), 8, &mut token) == 0 {
            return Err("无法读取 Windows 当前账户".into());
        }
        let token = OwnedHandle(token);
        let mut needed = 0;
        GetTokenInformation(token.0, 1, null_mut(), 0, &mut needed);
        if needed == 0 || needed > 1024 * 1024 {
            return Err("Windows 账户令牌无效".into());
        }
        let mut buffer = vec![0usize; (needed as usize).div_ceil(std::mem::size_of::<usize>())];
        if GetTokenInformation(token.0, 1, buffer.as_mut_ptr().cast(), needed, &mut needed) == 0 {
            return Err("无法读取 Windows 账户 SID".into());
        }
        let user = &*buffer.as_ptr().cast::<TokenUser>();
        let mut owner = null_mut();
        let mut existing = null_mut();
        let code = GetSecurityInfo(
            object.0,
            1,
            OWNER_SECURITY_INFORMATION,
            &mut owner,
            null_mut(),
            null_mut(),
            null_mut(),
            &mut existing,
        );
        let _existing = LocalMemory(existing);
        if code != 0 || owner.is_null() {
            return Err("无法校验 Windows 配置路径属主".into());
        }
        let change_owner = EqualSid(owner, user.sid) == 0;
        if change_owner {
            // Elevated Windows tokens can create files owned by their default
            // owner group (e.g. Administrators). Accept only that token's own
            // default owner, then normalize ownership to its individual user SID.
            let mut owner_size = 0;
            GetTokenInformation(token.0, 4, null_mut(), 0, &mut owner_size);
            if owner_size == 0 || owner_size > 1024 * 1024 {
                return Err("Windows 默认属主令牌无效".into());
            }
            let mut owner_buffer =
                vec![0usize; (owner_size as usize).div_ceil(std::mem::size_of::<usize>())];
            if GetTokenInformation(
                token.0,
                4,
                owner_buffer.as_mut_ptr().cast(),
                owner_size,
                &mut owner_size,
            ) == 0
                || EqualSid(owner, *owner_buffer.as_ptr().cast::<Sid>()) == 0
            {
                return Err("配置路径必须属于当前 Windows 账户".into());
            }
        }
        let mut sid_text = null_mut();
        if ConvertSidToStringSidW(user.sid, &mut sid_text) == 0 {
            return Err("无法转换 Windows 账户 SID".into());
        }
        let _sid_text = LocalMemory(sid_text.cast());
        let mut length = 0;
        while *sid_text.add(length) != 0 {
            length += 1;
        }
        let sid = String::from_utf16(std::slice::from_raw_parts(sid_text, length))
            .map_err(|_| "Windows SID 格式无效")?;
        let inheritance = if directory { "OICI" } else { "" };
        let sddl: Vec<u16> = format!("D:P(A;{inheritance};FA;;;{sid})")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let mut descriptor = null_mut();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            1,
            &mut descriptor,
            null_mut(),
        ) == 0
        {
            return Err("无法创建 Windows 私有 ACL".into());
        }
        let _descriptor = LocalMemory(descriptor);
        let mut present = 0;
        let mut dacl = null_mut();
        let mut defaulted = 0;
        if GetSecurityDescriptorDacl(descriptor, &mut present, &mut dacl, &mut defaulted) == 0
            || present == 0
            || dacl.is_null()
        {
            return Err("Windows 私有 ACL 无效".into());
        }
        if SetSecurityInfo(
            object.0,
            1,
            DACL_SECURITY_INFORMATION
                | PROTECTED_DACL_SECURITY_INFORMATION
                | if change_owner {
                    OWNER_SECURITY_INFORMATION
                } else {
                    0
                },
            if change_owner { user.sid } else { null_mut() },
            null_mut(),
            dacl,
            null_mut(),
        ) != 0
        {
            return Err("无法限制 Windows 配置权限，请使用支持账户 ACL 的本地磁盘".into());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(windows)]
    #[test]
    fn native_dacl_is_protected_and_only_grants_the_owner() {
        #[repr(C)]
        struct Acl {
            revision: u8,
            reserved: u8,
            size: u16,
            count: u16,
            reserved2: u16,
        }
        #[link(name = "advapi32")]
        unsafe extern "system" {
            fn GetSecurityDescriptorControl(
                descriptor: *const c_void,
                control: *mut u16,
                revision: *mut u32,
            ) -> i32;
            fn GetAce(acl: *const c_void, index: u32, ace: *mut *mut c_void) -> i32;
        }
        let directory = std::env::temp_dir().join(format!("nautilus-acl-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        private_path(&directory, true).unwrap();
        let file = directory.join("unicode-用户.env");
        std::fs::write(&file, "offline-key").unwrap();
        private_path(&file, false).unwrap();
        for path in [&directory, &file] {
            let path = wide_path(path).unwrap();
            unsafe {
                let handle = OwnedHandle(CreateFileW(
                    path.as_ptr(),
                    READ_CONTROL,
                    3,
                    null(),
                    3,
                    0x0220_0000,
                    null_mut(),
                ));
                assert_ne!(handle.0 as isize, -1);
                let mut owner = null_mut();
                let mut dacl = null_mut();
                let mut descriptor = null_mut();
                assert_eq!(
                    GetSecurityInfo(
                        handle.0,
                        1,
                        OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                        &mut owner,
                        null_mut(),
                        &mut dacl,
                        null_mut(),
                        &mut descriptor
                    ),
                    0
                );
                let _memory = LocalMemory(descriptor);
                let mut control = 0;
                let mut revision = 0;
                assert_ne!(
                    GetSecurityDescriptorControl(descriptor, &mut control, &mut revision),
                    0
                );
                assert_ne!(control & 0x1000, 0);
                assert!(!dacl.is_null());
                assert_eq!((*dacl.cast::<Acl>()).count, 1);
                let mut ace = null_mut();
                assert_ne!(GetAce(dacl, 0, &mut ace), 0);
                assert_eq!(*ace.cast::<u8>(), 0);
                assert_ne!(EqualSid(owner, ace.cast::<u8>().add(8).cast()), 0);
            }
        }
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn bindings_match_windows_file_information_layout_and_paths_keep_unicode() {
        assert_eq!(std::mem::size_of::<FileInformation>(), 52);
        assert_eq!(std::mem::size_of::<FileTime>(), 8);
        let path = wide_path(Path::new("C:/用户/Francis Gray/Nautilus/.env")).unwrap();
        assert_eq!(
            String::from_utf16(&path[..path.len() - 1]).unwrap(),
            "C:/用户/Francis Gray/Nautilus/.env"
        );
        assert_eq!(path.last(), Some(&0));
    }
}
