use sha1::{Digest, Sha1};
use std::cell::RefCell;
use std::ffi::{CStr, CString};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::raw::{c_char, c_int, c_longlong};
use std::path::{Component, Path, PathBuf};
use std::time::Duration;
use url::Url;
use zip::ZipArchive;

thread_local! {
    static LAST_ERROR: RefCell<CString> =
        RefCell::new(CString::new("No native error").expect("static error has no NUL"));
}

const USER_AGENT: &str = "TSI-Launcher/0.3";
const MAX_REDIRECTS: usize = 5;
const MAX_ZIP_FILE_BYTES: u64 = 1024 * 1024 * 1024;

fn set_error(message: impl ToString) {
    let clean = message.to_string().replace('\0', " ");
    LAST_ERROR.with(|slot| {
        *slot.borrow_mut() = CString::new(clean)
            .unwrap_or_else(|_| CString::new("Unknown native error").unwrap());
    });
}

#[no_mangle]
pub extern "C" fn tsi_last_error() -> *const c_char {
    LAST_ERROR.with(|slot| slot.borrow().as_ptr())
}

unsafe fn string_arg<'a>(value: *const c_char, label: &str) -> Result<&'a str, String> {
    if value.is_null() {
        return Err(format!("{label} is null"));
    }
    CStr::from_ptr(value)
        .to_str()
        .map_err(|_| format!("{label} is not valid UTF-8"))
}

fn safe_segment(value: &str, label: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > 100
        || value == "."
        || value == ".."
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._+-".contains(&b))
    {
        return Err(format!("Invalid {label}"));
    }
    Ok(())
}

fn ensure_directory(path: &Path) -> Result<(), String> {
    fs::create_dir_all(path).map_err(|e| format!("Cannot create {}: {e}", path.display()))?;
    if !path.is_dir() {
        return Err(format!("Expected a directory: {}", path.display()));
    }
    Ok(())
}

fn sha1_file(path: &Path) -> Result<String, String> {
    let mut input = File::open(path)
        .map_err(|e| format!("Cannot open {} for SHA-1: {e}", path.display()))?;
    let mut digest = Sha1::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let count = input
            .read(&mut buffer)
            .map_err(|e| format!("Cannot read {}: {e}", path.display()))?;
        if count == 0 {
            break;
        }
        digest.update(&buffer[..count]);
    }
    Ok(hex::encode(digest.finalize()))
}

fn verified_file(path: &Path, expected_sha1: Option<&str>, expected_size: i64) -> bool {
    let metadata = match fs::metadata(path) {
        Ok(value) if value.is_file() => value,
        _ => return false,
    };
    if expected_size >= 0 && metadata.len() != expected_size as u64 {
        return false;
    }
    match expected_sha1 {
        Some(expected) if !expected.is_empty() => {
            if expected.len() != 40 || !expected.bytes().all(|b| b.is_ascii_hexdigit()) {
                return false;
            }
            sha1_file(path)
                .map(|actual| actual.eq_ignore_ascii_case(expected))
                .unwrap_or(false)
        }
        _ => expected_size >= 0,
    }
}

fn validate_download_url(raw: &str) -> Result<Url, String> {
    let url = Url::parse(raw).map_err(|e| format!("Invalid download URL: {e}"))?;
    let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
    let allowed = host == "mojang.com"
        || host.ends_with(".mojang.com")
        || host == "minecraft.net"
        || host.ends_with(".minecraft.net")
        || host == "libraries.minecraft.net"
        || host == "repo.maven.apache.org"
        || host == "repo1.maven.org"
        || host == "resources.download.minecraft.net";
    if url.scheme() != "https" || !allowed || !url.username().is_empty() || url.password().is_some() {
        return Err(format!(
            "Refusing non-HTTPS or untrusted Minecraft download URL: {host}"
        ));
    }
    Ok(url)
}

fn open_download(url: &Url) -> Result<ureq::Response, String> {
    let agent = ureq::AgentBuilder::new()
        .redirects(0)
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(90))
        .build();
    let mut current = url.clone();
    for hop in 0..=MAX_REDIRECTS {
        validate_download_url(current.as_str())?;
        let result = agent
            .get(current.as_str())
            .set("User-Agent", USER_AGENT)
            .call();
        match result {
            Ok(response) => return Ok(response),
            Err(ureq::Error::Status(status, response))
                if (300..400).contains(&status) && hop < MAX_REDIRECTS =>
            {
                let location = response
                    .header("Location")
                    .ok_or_else(|| format!("HTTP {status} redirect has no Location header"))?;
                current = current
                    .join(location)
                    .map_err(|e| format!("Invalid redirect URL: {e}"))?;
            }
            Err(ureq::Error::Status(status, _)) => {
                return Err(format!("Download failed with HTTP {status}"));
            }
            Err(ureq::Error::Transport(error)) => {
                return Err(format!("Download transport error: {error}"));
            }
        }
    }
    Err("Download exceeded the redirect limit".to_string())
}

fn replace_atomically(temporary: &Path, target: &Path) -> Result<(), String> {
    let parent = target
        .parent()
        .ok_or_else(|| "Download target has no parent directory".to_string())?;
    let backup = parent.join(format!(
        "{}.tsi-backup",
        target.file_name().and_then(|n| n.to_str()).unwrap_or("download")
    ));
    if backup.exists() {
        fs::remove_file(&backup).map_err(|e| format!("Cannot clear stale backup: {e}"))?;
    }
    let had_target = target.exists();
    if had_target {
        fs::rename(target, &backup).map_err(|e| format!("Cannot stage previous file: {e}"))?;
    }
    if let Err(error) = fs::rename(temporary, target) {
        if had_target {
            let _ = fs::rename(&backup, target);
        }
        return Err(format!("Cannot install downloaded file: {error}"));
    }
    if backup.exists() {
        let _ = fs::remove_file(backup);
    }
    Ok(())
}

fn download_verified(
    raw_url: &str,
    destination: &Path,
    expected_sha1: Option<&str>,
    expected_size: i64,
) -> Result<(), String> {
    if let Some(hash) = expected_sha1 {
        if !hash.is_empty()
            && (hash.len() != 40 || !hash.bytes().all(|b| b.is_ascii_hexdigit()))
        {
            return Err("Expected SHA-1 must contain exactly 40 hexadecimal characters".into());
        }
    }
    let url = validate_download_url(raw_url)?;
    let parent = destination
        .parent()
        .ok_or_else(|| "Download target has no parent directory".to_string())?;
    ensure_directory(parent)?;
    let parent = parent
        .canonicalize()
        .map_err(|e| format!("Cannot resolve download folder: {e}"))?;
    let file_name = destination
        .file_name()
        .ok_or_else(|| "Download target has no filename".to_string())?;
    let target = parent.join(file_name);
    let temporary = parent.join(format!(
        "{}.part",
        file_name.to_string_lossy()
    ));
    if temporary.exists() {
        fs::remove_file(&temporary)
            .map_err(|e| format!("Cannot remove incomplete download: {e}"))?;
    }

    let result = (|| {
        let response = open_download(&url)?;
        let status = response.status();
        if !(200..300).contains(&status) {
            return Err(format!("Download failed with HTTP {status}"));
        }
        let mut input = response.into_reader();
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|e| format!("Cannot create temporary download: {e}"))?;
        let mut digest = Sha1::new();
        let mut total = 0u64;
        let mut buffer = [0u8; 64 * 1024];
        loop {
            let count = input
                .read(&mut buffer)
                .map_err(|e| format!("Download read failed: {e}"))?;
            if count == 0 {
                break;
            }
            output
                .write_all(&buffer[..count])
                .map_err(|e| format!("Download write failed: {e}"))?;
            digest.update(&buffer[..count]);
            total = total
                .checked_add(count as u64)
                .ok_or_else(|| "Download size overflow".to_string())?;
        }
        output
            .sync_all()
            .map_err(|e| format!("Cannot sync downloaded file: {e}"))?;
        drop(output);

        if expected_size >= 0 && total != expected_size as u64 {
            return Err(format!(
                "File size verification failed: expected {}, received {total}",
                expected_size
            ));
        }
        if let Some(expected) = expected_sha1.filter(|v| !v.is_empty()) {
            let actual = hex::encode(digest.finalize());
            if !actual.eq_ignore_ascii_case(expected) {
                return Err(format!("SHA-1 verification failed for {}", file_name.to_string_lossy()));
            }
        }
        replace_atomically(&temporary, &target)
    })();

    if result.is_err() && temporary.exists() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn safe_zip_relative(entry_name: &str, archive_path: &Path) -> Result<PathBuf, String> {
    if entry_name.contains('\\') || entry_name.contains(':') {
        return Err(format!("Unsafe ZIP path in {}", archive_path.display()));
    }
    let enclosed = Path::new(entry_name);
    let mut output = PathBuf::new();
    for component in enclosed.components() {
        match component {
            Component::Normal(value) => output.push(value),
            Component::CurDir => {}
            _ => return Err(format!("ZIP entry escapes destination: {entry_name}")),
        }
    }
    if output.as_os_str().is_empty() {
        return Err(format!("Empty ZIP entry path in {}", archive_path.display()));
    }
    Ok(output)
}

fn extract_zip(
    archive_path: &Path,
    destination: &Path,
    max_entries: usize,
    max_uncompressed_bytes: u64,
) -> Result<i32, String> {
    if !archive_path.is_file() || fs::metadata(archive_path).map(|m| m.len()).unwrap_or(0) == 0 {
        return Err("The selected ZIP is empty or unavailable".into());
    }
    ensure_directory(destination)?;
    let root = destination
        .canonicalize()
        .map_err(|e| format!("Cannot resolve ZIP destination: {e}"))?;
    let input = File::open(archive_path)
        .map_err(|e| format!("Cannot open ZIP archive: {e}"))?;
    let mut archive = ZipArchive::new(input)
        .map_err(|e| format!("Invalid ZIP archive: {e}"))?;
    if archive.len() > max_entries {
        return Err(format!("ZIP contains too many entries: {}", archive.len()));
    }

    let mut entries_written = 0usize;
    let mut total_written = 0u64;
    let mut buffer = [0u8; 64 * 1024];

    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|e| format!("Cannot read ZIP entry {index}: {e}"))?;
        let name = entry.name().to_string();
        let relative = safe_zip_relative(&name, archive_path)?;
        if entry
            .unix_mode()
            .map(|mode| mode & 0o170000 == 0o120000)
            .unwrap_or(false)
        {
            return Err(format!("ZIP symbolic links are not allowed: {name}"));
        }

        let target = root.join(&relative);
        if entry.is_dir() {
            ensure_directory(&target)?;
            let canonical = target
                .canonicalize()
                .map_err(|e| format!("Cannot resolve ZIP directory: {e}"))?;
            if !canonical.starts_with(&root) {
                return Err(format!("ZIP directory escapes destination: {name}"));
            }
            continue;
        }

        if entry.size() > MAX_ZIP_FILE_BYTES {
            return Err(format!("ZIP entry exceeds the 1 GB per-file limit: {name}"));
        }
        if entry.size() > max_uncompressed_bytes.saturating_sub(total_written) {
            return Err("ZIP exceeds the configured uncompressed-size limit".into());
        }

        let parent = target
            .parent()
            .ok_or_else(|| format!("ZIP entry has no parent: {name}"))?;
        ensure_directory(parent)?;
        let canonical_parent = parent
            .canonicalize()
            .map_err(|e| format!("Cannot resolve ZIP entry parent: {e}"))?;
        if !canonical_parent.starts_with(&root) {
            return Err(format!("ZIP entry escapes destination: {name}"));
        }
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&target)
            .map_err(|e| format!("Cannot create extracted file {}: {e}", target.display()))?;
        let mut file_bytes = 0u64;
        loop {
            let count = entry
                .read(&mut buffer)
                .map_err(|e| format!("Cannot decompress ZIP entry {name}: {e}"))?;
            if count == 0 {
                break;
            }
            file_bytes = file_bytes
                .checked_add(count as u64)
                .ok_or_else(|| "ZIP entry size overflow".to_string())?;
            total_written = total_written
                .checked_add(count as u64)
                .ok_or_else(|| "ZIP expanded-size overflow".to_string())?;
            if file_bytes > MAX_ZIP_FILE_BYTES || total_written > max_uncompressed_bytes {
                return Err("ZIP expanded beyond its configured safety limit".into());
            }
            output
                .write_all(&buffer[..count])
                .map_err(|e| format!("Cannot write extracted file: {e}"))?;
        }
        output
            .sync_all()
            .map_err(|e| format!("Cannot sync extracted file: {e}"))?;
        entries_written += 1;
    }
    Ok(entries_written as i32)
}

fn ensure_game_folders(game: &Path) -> Result<(), String> {
    ensure_directory(game)?;
    for name in ["mods", "config", "saves", "resourcepacks", "shaderpacks", "screenshots", "logs"] {
        ensure_directory(&game.join(name))?;
    }
    Ok(())
}

fn ensure_instance_layout(root: &Path, id: &str, version: &str) -> Result<(), String> {
    safe_segment(id, "instance ID")?;
    safe_segment(version, "Minecraft version ID")?;
    ensure_directory(root)?;
    let root = root
        .canonicalize()
        .map_err(|e| format!("Cannot resolve Minecraft root: {e}"))?;

    let versions = root.join("versions");
    let instances = root.join("instances");
    let assets = root.join("assets");
    let libraries = root.join("libraries");
    for directory in [&versions, &instances, &assets, &libraries] {
        ensure_directory(directory)?;
        if !directory.canonicalize().map_err(|e| e.to_string())?.starts_with(&root) {
            return Err("Minecraft directory contains a path outside its root".into());
        }
    }

    let version_dir = versions.join(version);
    let instance_dir = instances.join(id);
    ensure_directory(&version_dir)?;
    ensure_directory(&instance_dir)?;
    let game = instance_dir.join("game");
    ensure_game_folders(&game)?;

    for candidate in [&version_dir, &instance_dir, &game] {
        if !candidate.canonicalize().map_err(|e| e.to_string())?.starts_with(&root) {
            return Err("Instance path escapes Minecraft storage".into());
        }
    }
    Ok(())
}

fn copy_tree_inner(source: &Path, destination: &Path, root: &Path) -> Result<(), String> {
    let metadata = fs::symlink_metadata(source)
        .map_err(|e| format!("Cannot inspect {}: {e}", source.display()))?;
    if metadata.file_type().is_symlink() {
        return Err(format!("Refusing to follow symbolic link: {}", source.display()));
    }
    if metadata.is_dir() {
        ensure_directory(destination)?;
        let canonical = destination.canonicalize().map_err(|e| e.to_string())?;
        if !canonical.starts_with(root) {
            return Err("Copy destination escapes its root".into());
        }
        for entry in fs::read_dir(source).map_err(|e| format!("Cannot read source folder: {e}"))? {
            let entry = entry.map_err(|e| format!("Cannot read source entry: {e}"))?;
            copy_tree_inner(&entry.path(), &destination.join(entry.file_name()), root)?;
        }
    } else if metadata.is_file() {
        let parent = destination
            .parent()
            .ok_or_else(|| "Copy destination has no parent".to_string())?;
        ensure_directory(parent)?;
        let canonical = parent.canonicalize().map_err(|e| e.to_string())?;
        if !canonical.starts_with(root) {
            return Err("Copy destination escapes its root".into());
        }
        if !destination.exists() {
            fs::copy(source, destination)
                .map_err(|e| format!("Cannot copy {}: {e}", source.display()))?;
        }
    }
    Ok(())
}

fn copy_tree(source: &Path, destination: &Path) -> Result<(), String> {
    let source_metadata = fs::symlink_metadata(source)
        .map_err(|e| format!("Cannot inspect copy source: {e}"))?;
    if source_metadata.file_type().is_symlink() {
        return Err("Refusing to copy a symbolic-link source".into());
    }
    let source = source
        .canonicalize()
        .map_err(|e| format!("Cannot resolve copy source: {e}"))?;
    if source.is_dir() {
        if destination.exists()
            && fs::symlink_metadata(destination)
                .map(|metadata| metadata.file_type().is_symlink())
                .unwrap_or(false)
        {
            return Err("Refusing to copy into a symbolic-link destination".into());
        }
        ensure_directory(destination)?;
        let root = destination
            .canonicalize()
            .map_err(|e| format!("Cannot resolve copy destination: {e}"))?;
        return copy_tree_inner(&source, &root, &root);
    }

    let parent = destination
        .parent()
        .ok_or_else(|| "Copy destination has no parent".to_string())?;
    ensure_directory(parent)?;
    let root = parent
        .canonicalize()
        .map_err(|e| format!("Cannot resolve copy destination folder: {e}"))?;
    copy_tree_inner(&source, destination, &root)
}

fn delete_tree_inner(path: &Path) -> Result<(), String> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|e| format!("Cannot inspect {}: {e}", path.display()))?;
    if metadata.file_type().is_symlink() || metadata.is_file() {
        fs::remove_file(path).map_err(|e| format!("Cannot delete {}: {e}", path.display()))?;
    } else if metadata.is_dir() {
        for entry in fs::read_dir(path).map_err(|e| format!("Cannot read {}: {e}", path.display()))? {
            let entry = entry.map_err(|e| e.to_string())?;
            delete_tree_inner(&entry.path())?;
        }
        fs::remove_dir(path).map_err(|e| format!("Cannot delete {}: {e}", path.display()))?;
    }
    Ok(())
}

fn delete_instance(root: &Path, id: &str) -> Result<(), String> {
    safe_segment(id, "instance ID")?;
    let root = root
        .canonicalize()
        .map_err(|e| format!("Cannot resolve Minecraft root: {e}"))?;
    let instances_root = root.join("instances");
    let instances_root = instances_root
        .canonicalize()
        .map_err(|e| format!("Cannot resolve instances directory: {e}"))?;
    if !instances_root.starts_with(&root) {
        return Err("Instances directory points outside Minecraft storage".into());
    }
    let target = instances_root.join(id);
    let metadata = fs::symlink_metadata(&target)
        .map_err(|e| format!("Instance does not exist: {e}"))?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err("Selected instance is not a normal directory".into());
    }
    let canonical = target
        .canonicalize()
        .map_err(|e| format!("Cannot resolve selected instance: {e}"))?;
    if canonical.parent() != Some(instances_root.as_path()) {
        return Err("Refusing to delete data outside the instances directory".into());
    }
    let remaining = fs::read_dir(&instances_root)
        .map_err(|e| format!("Cannot list instances: {e}"))?
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().map(|t| t.is_dir()).unwrap_or(false))
        .filter(|entry| entry.path().join("tsi-instance.json").is_file())
        .count();
    if remaining <= 1 {
        return Err("The last remaining instance cannot be deleted".into());
    }
    delete_tree_inner(&canonical)
}

fn ffi_result<F>(operation: F) -> c_int
where
    F: FnOnce() -> Result<(), String>,
{
    match operation() {
        Ok(()) => 0,
        Err(error) => {
            set_error(error);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn tsi_sha1_matches(path: *const c_char, expected: *const c_char) -> c_int {
    let result = (|| {
        let path = Path::new(unsafe { string_arg(path, "File path") }?);
        let expected = unsafe { string_arg(expected, "Expected SHA-1") }?;
        if expected.len() != 40 || !expected.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("Expected SHA-1 must contain 40 hexadecimal characters".into());
        }
        Ok(if verified_file(path, Some(expected), -1) { 1 } else { 0 })
    })();
    match result {
        Ok(value) => value,
        Err(error) => {
            set_error(error);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn tsi_is_verified_file(
    path: *const c_char,
    expected: *const c_char,
    expected_size: c_longlong,
) -> c_int {
    let result = (|| {
        let path = Path::new(unsafe { string_arg(path, "File path") }?);
        let expected = if expected.is_null() {
            None
        } else {
            let value = unsafe { string_arg(expected, "Expected SHA-1") }?;
            if value.is_empty() { None } else { Some(value) }
        };
        Ok(if verified_file(path, expected, expected_size) { 1 } else { 0 })
    })();
    match result {
        Ok(value) => value,
        Err(error) => {
            set_error(error);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn tsi_download_verified(
    url: *const c_char,
    destination: *const c_char,
    expected: *const c_char,
    expected_size: c_longlong,
) -> c_int {
    ffi_result(|| {
        let url = unsafe { string_arg(url, "Download URL") }?;
        let destination = Path::new(unsafe { string_arg(destination, "Download destination") }?);
        let expected = if expected.is_null() {
            None
        } else {
            let value = unsafe { string_arg(expected, "Expected SHA-1") }?;
            if value.is_empty() { None } else { Some(value) }
        };
        download_verified(url, destination, expected, expected_size)
    })
}

#[no_mangle]
pub unsafe extern "C" fn tsi_extract_zip(
    archive: *const c_char,
    destination: *const c_char,
    max_entries: c_longlong,
    max_bytes: c_longlong,
) -> c_int {
    let result = (|| {
        let archive = Path::new(unsafe { string_arg(archive, "ZIP archive path") }?);
        let destination = Path::new(unsafe { string_arg(destination, "ZIP destination") }?);
        if max_entries <= 0 || max_bytes <= 0 {
            return Err("ZIP safety limits must be positive".into());
        }
        extract_zip(archive, destination, max_entries as usize, max_bytes as u64)
    })();
    match result {
        Ok(count) => count,
        Err(error) => {
            set_error(error);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn tsi_ensure_game_folders(game: *const c_char) -> c_int {
    ffi_result(|| ensure_game_folders(Path::new(unsafe { string_arg(game, "Game directory") }?)))
}

#[no_mangle]
pub unsafe extern "C" fn tsi_ensure_instance_layout(
    root: *const c_char,
    id: *const c_char,
    version: *const c_char,
) -> c_int {
    ffi_result(|| {
        let root = Path::new(unsafe { string_arg(root, "Minecraft root") }?);
        let id = unsafe { string_arg(id, "Instance ID") }?;
        let version = unsafe { string_arg(version, "Minecraft version ID") }?;
        ensure_instance_layout(root, id, version)
    })
}

#[no_mangle]
pub unsafe extern "C" fn tsi_delete_instance(
    root: *const c_char,
    id: *const c_char,
) -> c_int {
    ffi_result(|| {
        let root = Path::new(unsafe { string_arg(root, "Minecraft root") }?);
        let id = unsafe { string_arg(id, "Instance ID") }?;
        delete_instance(root, id)
    })
}

#[no_mangle]
pub unsafe extern "C" fn tsi_copy_tree(
    source: *const c_char,
    destination: *const c_char,
) -> c_int {
    ffi_result(|| {
        let source = Path::new(unsafe { string_arg(source, "Copy source") }?);
        let destination = Path::new(unsafe { string_arg(destination, "Copy destination") }?);
        copy_tree(source, destination)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::tempdir;
    use zip::write::FileOptions;

    #[test]
    fn validates_only_safe_instance_segments() {
        assert!(safe_segment("fabric-1.21.1", "version").is_ok());
        assert!(safe_segment("../outside", "instance").is_err());
        assert!(safe_segment("folder/name", "instance").is_err());
        assert!(safe_segment("C:temp", "instance").is_err());
    }

    #[test]
    fn verifies_sha1_and_size() {
        let temp = tempdir().unwrap();
        let file = temp.path().join("hello.txt");
        fs::write(&file, b"hello").unwrap();
        assert!(verified_file(
            &file,
            Some("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"),
            5
        ));
        assert!(!verified_file(&file, Some("0000000000000000000000000000000000000000"), 5));
        assert!(!verified_file(&file, None, 6));
    }

    #[test]
    fn downloads_reject_untrusted_hosts() {
        assert!(validate_download_url("https://piston-meta.mojang.com/file").is_ok());
        assert!(validate_download_url("http://piston-meta.mojang.com/file").is_err());
        assert!(validate_download_url("https://example.com/file").is_err());
    }

    #[test]
    fn extracts_zip_without_path_traversal() {
        let temp = tempdir().unwrap();
        let archive_path = temp.path().join("safe.zip");
        {
            let file = File::create(&archive_path).unwrap();
            let mut zip = zip::ZipWriter::new(file);
            zip.start_file("instance/.minecraft/options.txt", FileOptions::default()).unwrap();
            zip.write_all(b"renderDistance:4").unwrap();
            zip.finish().unwrap();
        }
        let destination = temp.path().join("out");
        let count = extract_zip(&archive_path, &destination, 100, 1024).unwrap();
        assert_eq!(count, 1);
        assert_eq!(
            fs::read(destination.join("instance/.minecraft/options.txt")).unwrap(),
            b"renderDistance:4"
        );
    }

    #[test]
    fn creates_expected_instance_layout() {
        let temp = tempdir().unwrap();
        ensure_instance_layout(temp.path(), "world-1", "1.21.1").unwrap();
        assert!(temp.path().join("instances/world-1/game/saves").is_dir());
        assert!(temp.path().join("versions/1.21.1").is_dir());
        assert!(temp.path().join("assets").is_dir());
        assert!(temp.path().join("libraries").is_dir());
    }
}
