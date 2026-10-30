// soma-service-helper: the only root action the Mac runner may ask for (soma_services.LiveBackend.privileged).
//
// It trusts nothing the runner says. Every restart needs the owner's own signature, verified here against the pinned
// root-owned owner keys, for this exact service, this host and an unexpired approval, used at most once (recorded in a
// root-only folder before anything runs). So a process running as the runner's user that can call it through sudo still
// cannot restart anything without a fresh owner-signed approval.
//
//   sudo -n /Library/PrivilegedHelperTools/soma-service-helper  < {approval, nonce, service, host, expires_at,
//                                                                   owner_key_id, owner_signature[, owner_payloads]}
// No arguments are accepted (the sudo rule allows none); the proof comes on stdin, so it never appears in argv or logs.
// Exit: 0 restarted, 64 usage, 65 refused, 77 not root, other = launchctl's own status.
//
// Built and installed root:wheel 0755 by install-service-helper.sh (Boss runs it himself). Its service table must match
// soma_services.SERVICES (test-services.py checks it). Its test mode exists only in a test build (swiftc -D
// SOMA_HELPER_TESTING, test-services.py): there, unprivileged with SOMA_HELPER_TEST_DIR, it prints the command instead of
// running it. The installed build is compiled without that flag, so it has no test mode at all.
import CryptoKit
import Foundation

let services: [String: String] = [
    "tailshare-serve": "com.tailshare.serve",
    "tailshare-receive": "com.tailshare.receive",
    "herd": "dev.herd.node",
    "embeddings": "dev.omalauncher.embed-server",
    "whisper": "dev.omalauncher.whisper-server",
]
// Not tailscaled: every service here runs as the runner's own user, but Homebrew's tailscaled runs as root from a binary that user
// owns, so restarting it would run whatever is there as root. And a Mac without it can't be reached to be asked.
let payloadTag = "soma-service-restart-v1"
let planTag = "soma-service-restart-plan-v1"
let maxPlan = 16, maxPayload = 2048, maxKeys = 8, maxInput = 65_536
let ttlMs: Int64 = 120_000, skewMs: Int64 = 10_000

func fail(_ code: String, _ status: Int32 = 65) -> Never {
    FileHandle.standardError.write(("refused: " + code + "\n").data(using: .utf8)!)
    exit(status)
}

let root = geteuid() == 0
#if SOMA_HELPER_TESTING
let testDir = root ? nil : ProcessInfo.processInfo.environment["SOMA_HELPER_TEST_DIR"]
let injectedFailure = testDir == nil ? nil : ProcessInfo.processInfo.environment["SOMA_HELPER_FAIL"]  // write | fsync | dirsync
#else
let testDir: String? = nil
let injectedFailure: String? = nil
#endif
if !root && testDir == nil { fail("not_root", 77) }
if CommandLine.arguments.count != 1 { fail("usage: no arguments; the owner proof comes on stdin", 64) }
let base = testDir ?? "/Library/Application Support/Soma"
let trustedUIDs: Set<uid_t> = root ? [0] : [0, geteuid()]

// A file (and every folder above it) that only root -- or, in tests, this user -- could have written.
func safeFile(_ path: String, maxSize: Int) -> Data {
    var info = stat()
    guard path.hasPrefix("/"), !path.split(separator: "/").contains(".."), lstat(path, &info) == 0 else { fail("missing:" + (path as NSString).lastPathComponent) }
    guard (info.st_mode & S_IFMT) == S_IFREG, trustedUIDs.contains(info.st_uid), info.st_mode & (S_IWGRP | S_IWOTH) == 0, Int(info.st_size) <= maxSize
    else { fail("unsafe:" + (path as NSString).lastPathComponent) }
    var folder = (path as NSString).deletingLastPathComponent
    while true {
        var dir = stat()
        guard lstat(folder, &dir) == 0, (dir.st_mode & S_IFMT) == S_IFDIR, trustedUIDs.contains(dir.st_uid), dir.st_mode & (S_IWGRP | S_IWOTH) == 0
        else { fail("unsafe_folder") }
        if folder == "/" { break }
        folder = (folder as NSString).deletingLastPathComponent
    }
    let fd = open(path, O_RDONLY | O_NOFOLLOW)
    guard fd >= 0 else { fail("unreadable") }
    defer { close(fd) }
    var opened = stat()
    guard fstat(fd, &opened) == 0, opened.st_ino == info.st_ino, opened.st_dev == info.st_dev else { fail("changed") }
    return FileHandle(fileDescriptor: fd, closeOnDealloc: false).readData(ofLength: maxSize + 1)
}

func object(_ data: Data) -> [String: Any] {
    guard let value = try? JSONSerialization.jsonObject(with: data), let dict = value as? [String: Any] else { fail("not_json") }
    return dict
}

func hex(_ digest: SHA256.Digest) -> String { digest.map { String(format: "%02x", $0) }.joined() }

// ---- the request ----
// A caller that never finishes its input must not keep a root process waiting: SIGALRM's default action ends it.
alarm(5)
let input = FileHandle.standardInput.readData(ofLength: maxInput + 1)
alarm(0)
if input.count > maxInput { fail("too_large") }
let proof = object(input)
guard let approval = proof["approval"] as? String, approval.range(of: "\\Asvc-[0-9a-f]{32}\\z", options: .regularExpression) != nil,
      let nonce = proof["nonce"] as? String, nonce.range(of: "\\A[A-Za-z0-9_-]{16,64}\\z", options: .regularExpression) != nil,
      let service = proof["service"] as? String, let label = services[service],
      let host = proof["host"] as? String, host.range(of: "\\A[a-z0-9-]{1,63}\\z", options: .regularExpression) != nil,
      let expires = (proof["expires_at"] as? NSNumber)?.int64Value,
      let keyId = proof["owner_key_id"] as? String, let signatureB64 = proof["owner_signature"] as? String,
      let signatureDer = Data(base64Encoded: signatureB64)
else { fail("bad_request") }

// ---- this host, pinned by the installer ----
let config = object(safeFile(base + "/helper.json", maxSize: 4096))
guard config["version"] as? Int == 1, let pinnedHost = config["host"] as? String, pinnedHost == host else { fail("wrong_host") }

// ---- time ----
let now = Int64(Date().timeIntervalSince1970 * 1000)
guard expires > now, expires <= now + ttlMs + skewMs else { fail("expired") }

// ---- the owner's key ----
let trust = object(safeFile(base + "/connections-owner-keys.json", maxSize: 65_536))
guard trust["version"] as? Int == 1, let keys = trust["keys"] as? [[String: Any]], keys.count <= maxKeys else { fail("trust_shape") }
var ownerKey: P256.Signing.PublicKey?
for entry in keys where (entry["revoked"] as? Bool) != true {
    guard entry["algorithm"] as? String == "ecdsa-p256-sha256", let b64 = entry["public_key"] as? String,
          let der = Data(base64Encoded: b64), let id = entry["key_id"] as? String, id == hex(SHA256.hash(data: der))
    else { fail("trust_shape") }
    if id == keyId { ownerKey = try? P256.Signing.PublicKey(derRepresentation: der) }
}
guard let ownerKey else { fail("owner_key") }

// ---- what the owner signed: this exact restart, alone or as one entry of a signed plan ----
let payload = [payloadTag, approval, nonce, service, host, String(expires)].joined(separator: "\n")
let ownB64 = Data(payload.utf8).base64EncodedString()
var message = Data(payload.utf8)
if let plan = proof["owner_payloads"] {
    guard let items = plan as? [String], (1...maxPlan).contains(items.count), Set(items).count == items.count, items.contains(ownB64) else { fail("bad_plan") }
    for item in items {
        guard let decoded = Data(base64Encoded: item), decoded.count <= maxPayload, decoded.starts(with: Data((payloadTag + "\n").utf8)) else { fail("bad_plan") }
    }
    message = Data(([planTag, String(items.count)] + items.sorted()).joined(separator: "\n").utf8)
}
guard let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureDer),
      ownerKey.isValidSignature(signature, for: message)
else { fail("bad_signature") }

// ---- once: recorded before anything runs, in a folder only root can write ----
let used = base + "/used-approvals"
mkdir(used, 0o700)
_ = safeFile(base + "/helper.json", maxSize: 4096)  // re-walks the folder chain; `used` itself is checked next
var usedInfo = stat()
guard lstat(used, &usedInfo) == 0, (usedInfo.st_mode & S_IFMT) == S_IFDIR, trustedUIDs.contains(usedInfo.st_uid), usedInfo.st_mode & 0o077 == 0
else { fail("unsafe_used_folder") }
if let old = try? FileManager.default.contentsOfDirectory(atPath: used) {  // approvals live 2 minutes; a day is plenty
    for name in old.prefix(2000) {
        let path = used + "/" + name
        if let attributes = try? FileManager.default.attributesOfItem(atPath: path), let date = attributes[.modificationDate] as? Date,
           date.timeIntervalSinceNow < -86_400 { unlink(path) }
    }
}
let mark = open(used + "/" + approval, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
if mark < 0 { fail(errno == EEXIST ? "approval_already_used" : "cannot_record_use") }
// The record must be on disk -- its bytes, the file and the folder entry -- before anything runs. Any failure refuses; the
// approval stays used (its marker exists or it simply expires), so nothing is ever retried with it.
let record = Array(String(expires).utf8)
let wrote = injectedFailure == "write" ? -1 : record.withUnsafeBufferPointer { write(mark, $0.baseAddress, $0.count) }
let fileSynced = injectedFailure == "fsync" ? -1 : fsync(mark)
close(mark)
guard wrote == record.count, fileSynced == 0 else { fail("cannot_record_use") }
let folderFd = open(used, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
guard folderFd >= 0 else { fail("cannot_record_use") }
let folderSynced = injectedFailure == "dirsync" ? -1 : fsync(folderFd)
close(folderFd)
guard folderSynced == 0 else { fail("cannot_record_use") }

// ---- the one fixed command ----
let argv = ["/bin/launchctl", "kickstart", "-k", "system/" + label]
if testDir != nil {
    print("would run: " + argv.joined(separator: " "))
    exit(0)
}
var pid: pid_t = 0
var cArgs = argv.map { strdup($0) } + [nil]
let spawned = posix_spawn(&pid, argv[0], nil, nil, &cArgs, [nil])
guard spawned == 0 else { fail("spawn_failed", 70) }
var status: Int32 = 0
waitpid(pid, &status, 0)
exit((status & 0x7f) == 0 ? (status >> 8) & 0xff : 70)
