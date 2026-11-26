# multihost-sftp

Picks up files from local folders and uploads them to SFTP servers. **Which files go where is pure configuration**:
every entry in `application.yml` becomes one Apache Camel route, built from a single XML route template. Adding a host
or a remote folder is a config change. No Java or XML changes.

Stack: Java 17 · Spring Boot 3.5 · Apache Camel 4.18 (XML DSL, `file` + `sftp` components) · Maven.
Camel 4.18 is the last Camel line built on Spring Boot 3.

---

## Quick start

| What | Command |
|---|---|
| Build and run all tests (embedded SFTP servers, no setup needed) | `mvn verify` |
| Try it by hand: two local SFTP servers + the app | `mvn spring-boot:test-run` |
| Run against real servers | see [Running for real](#running-for-real) |

### Try it locally

`mvn spring-boot:test-run` starts two embedded SFTP servers: **host A** on `127.0.0.1:2222` (key auth) and **host B**
on `127.0.0.1:2223` (password auth). It then starts the real application with the `local` profile
(`src/test/resources/application-local.yml`) and drops these mock files into `target/local-demo/out/`:

| Mock file | What happens |
|---|---|
| `bankA/PAY_<date>_001.csv`, `bankA/PAY_<date>_002.csv` | uploaded to host A `/inbound/payments` |
| `bankA/STMT_<date>.xml` | uploaded to host A `/inbound/statements` |
| `bankB/RPT_<date>.txt` | uploaded to host B `/upload/reports` |
| `bankA/notes.txt`, `bankA/PAY_<date>_003.txt`, `bankA/pay_<date>_004.csv`, `bankB/RPT_<date>.csv` | match no pattern: not sent, they stay |

A couple of seconds later the uploads are in `target/local-demo/sftp-hostA/inbound/...` and
`target/local-demo/sftp-hostB/upload/reports/`. The originals are moved to the **backup folders**
`target/local-demo/backup/bankA/` and `.../bankB/` (set by `doneDir` in `application-local.yml`); a failed upload would
go to `out/<bank>/error/`. Keys, passwords and host keys are generated on every run.

Drop more files while it runs, e.g. `echo "id;amount" > target/local-demo/out/bankA/PAY_20261127_100.csv`.
Stop with Ctrl+C. Options go in `-Dspring-boot.run.jvmArguments="..."`: `-Ddemo.mockFiles=false` skips the mock files,
`-Ddemo.portA=2322 -Ddemo.portB=2323` if 2222/2223 are taken.

---

## How it works

```
application.yml                      camel/sftp-transfer-template.xml          (one route per transfer)
sftp-transfer.transfers[*]  ──┐      <routeTemplate id="sftp-transfer">
                              │        from  file:{sourceDir}?include=regex&readLock=changed&idempotent...
SftpTransferProperties        │        onException: retry N times, log, then file -> errorDir
  binds + validates (fail fast)        to    sftp://{host}:{port}?fileName={remoteDir}{name}&tempFileName=...
                              │        log   "Transfer OK ..."            file -> doneDir
TransferRoutesBootstrap ──────┘
  templatedRoute("sftp-transfer").routeId(name).parameter(...)   x  every entry
```

| File | Role |
|---|---|
| `src/main/resources/camel/sftp-transfer-template.xml` | The **only** route definition: one `routeTemplate`. All behaviour lives here: polling, read lock, idempotency, retries, temp file, done/error folders, logging. |
| `SftpTransferProperties.java` | `@ConfigurationProperties("sftp-transfer")`. Binds the list and validates it at startup. Unknown keys are rejected. |
| `TransferRoutesBootstrap.java` | A `RouteBuilder` that turns each entry into template parameters. Camel adds templated routes after all XML templates are loaded. |
| `SftpTransferApplication.java` | Spring Boot main class. |
| `src/main/resources/application.yml` | Which files go where. |

---

## Configuration

```yaml
sftp-transfer:
  # global settings (pollDelay, knownHostsFile and strictHostKeyChecking can also be set per transfer)
  pollDelay: 10s
  knownHostsFile: ${SFTP_KNOWN_HOSTS_FILE:${user.home}/.ssh/known_hosts}
  strictHostKeyChecking: true
  maxRetries: 3
  retryDelay: 5s
  transfers:
    - name: bankA-payments
      sourceDir: /data/out/bankA
      filePattern: "PAY_.*\\.csv"
      host: sftp.banka.com
      port: 22
      username: user1
      privateKeyPath: /keys/banka_id_rsa
      remoteDir: /inbound/payments
    - name: bankB-reports
      sourceDir: /data/out/bankB
      filePattern: "RPT_.*\\.txt"
      host: sftp.bankb.com
      username: user2
      password: ${BANKB_SFTP_PASSWORD}     # from the environment, never written in the file
      remoteDir: /upload/reports
```

The same host may appear in several entries with different remote folders. The same source folder may appear with
different patterns.

### Per transfer (`sftp-transfer.transfers[*]`)

| Key | Required | Default | Meaning |
|---|---|---|---|
| `name` | yes | | Unique id (case-insensitive) and Camel route id. Letters, digits, `.`, `_`, `-`. |
| `sourceDir` | yes | | Local folder to poll (not recursive). Created at startup if missing. |
| `filePattern` | yes | | Java **regex** matched against the whole file name, case-sensitive (`PAY_.*\.csv`). Prefix `glob:` for an ant-style pattern (`glob:PAY_*.csv`, `*` = anything, `?` = one character). `regex:` is accepted too. |
| `host` | yes | | SFTP host. |
| `port` | | `22` | |
| `username` | yes | | |
| `password` | one of | | Always as a placeholder: `${SOME_ENV_VAR}`. |
| `privateKeyPath` | the two | | Private key file (OpenSSH or PEM). If both are set, the key is tried first. |
| `privateKeyPassphrase` | | | For an encrypted key, as a placeholder: `${SOME_ENV_VAR}`. |
| `remoteDir` | yes | | Remote folder. **Leading `/` = absolute** (`/inbound/payments`); without it, relative to the login folder (`outbox`). Must exist: remote folders are never created. |
| `pollDelay` | | global `pollDelay` | How often the folder is polled. |
| `doneDir` | | `<sourceDir>/done` | Where a file goes after a successful upload, e.g. a backup folder (`/data/backup/bankA`). |
| `errorDir` | | `<sourceDir>/error` | Where a file goes when the upload failed after all retries. |
| `knownHostsFile` | | global `knownHostsFile` | known_hosts file used to verify this server. |
| `strictHostKeyChecking` | | global (`true`) | `false` disables host key verification for this transfer (logged as a warning). |

### Global (`sftp-transfer.*`)

| Key | Default | Meaning |
|---|---|---|
| `pollDelay` | `10s` | Default poll interval. |
| `knownHostsFile` | `~/.ssh/known_hosts` | Default known_hosts file. |
| `strictHostKeyChecking` | `true` | Default for host key verification. |
| `maxRetries` | `3` | Retries after the first failed attempt; then the file goes to `errorDir`. |
| `retryDelay` | `5s` | Pause between attempts. |
| `connectTimeout` | `10s` | SSH connect timeout. |
| `readLockCheckInterval` | `1s` | A file is picked up only after its size/timestamp stayed unchanged this long. |
| `readLockTimeout` | `30s` | A file still changing after this long is skipped and retried at the next poll. |
| `idempotentDir` | `data/idempotent` | Holds one `<name>-uploaded.txt` per transfer: the files already uploaded. |

Durations accept `500ms`, `10s`, `5m` and so on. IDEs with Spring Boot support auto-complete the global keys
(metadata from `spring-boot-configuration-processor`).

### Fail fast

The configuration is validated before any route starts. The application refuses to start and lists **every**
problem: missing fields, duplicate names, two transfers watching exactly the same files, neither password nor key, an
unset environment variable, a missing key file, a missing known_hosts file while strict checking is on, an invalid
regex, a misspelled key, and so on. Secret values never appear in these messages.

```
***************************
APPLICATION FAILED TO START
***************************
Description:
Binding to target com.example.multihostsftp.SftpTransferProperties failed:

    Reason: sftp-transfer.transfers[0] (bankB-reports): 'host' is required

    Reason: sftp-transfer.transfers[0] (bankB-reports): 'password' refers to BANKB_SFTP_PASSWORD, which is not set - define that environment variable (or system property)
```

---

## Adding a new host or remote folder (config only)

1. **Trust the server's host key.** Add it to the known_hosts file and check the fingerprint against the one the
   server owner gives you, out of band:
   ```bash
   ssh-keyscan -p 22 sftp.newbank.com >> /etc/sftp-transfer/known_hosts
   ssh-keygen -lf /etc/sftp-transfer/known_hosts        # compare the fingerprints
   ```
   For a port other than 22 the entry looks like `[sftp.newbank.com]:2222 ssh-ed25519 AAAA...`.
2. **Provide the secret** as an environment variable, for example `NEWBANK_SFTP_PASSWORD=...` (or as a secret file,
   see below). Never put it in the YAML itself.
3. **Add an entry** to `sftp-transfer.transfers`:
   ```yaml
       - name: newbank-invoices
         sourceDir: /data/out/newbank
         filePattern: "glob:INV_*.xml"
         host: sftp.newbank.com
         username: acme
         password: ${NEWBANK_SFTP_PASSWORD}
         remoteDir: /incoming/invoices
   ```
   **Another remote folder on an existing host** is the same thing: a new entry with the same `host`/`username` and a
   different `remoteDir` (and usually a different `filePattern`, or a different `sourceDir`).
4. **Restart** the application. Routes are created at startup, and the log shows one line per transfer:
   ```
   Transfer 'newbank-invoices': /data/out/newbank [INV_.*\.xml] -> sftp://acme@sftp.newbank.com:22 /incoming/invoices (auth=password, hostKeyCheck=strict, poll every 10s)
   ```

If something is wrong (a typo in a key, a missing variable, a duplicate name), step 4 fails with the list of problems
and nothing is transferred.

---

## Running for real

```bash
mvn package
export BANKB_SFTP_PASSWORD='...'                         # one variable per secret used in the config
java -jar target/multihost-sftp-0.1.0-SNAPSHOT.jar --spring.config.additional-location=file:/etc/sftp-transfer/
```

* **Keep the config outside the jar.** `/etc/sftp-transfer/application.yml` (or `./config/application.yml` next to the
  jar) overrides the packaged file. Lists are **replaced, not merged**: the external `transfers` list is the complete
  list.
* **Secrets from files** (Docker/Kubernetes secrets): add `spring.config.import: "optional:configtree:/run/secrets/"`.
  A file `/run/secrets/BANKB_SFTP_PASSWORD` then satisfies `${BANKB_SFTP_PASSWORD}` exactly as an environment
  variable would.
* `idempotentDir` (`SFTP_TRANSFER_STATE_DIR`) should be on persistent storage. That is what prevents re-sending after
  a restart.
* There is no web server; `camel.main.run-controller=true` keeps the JVM running. On shutdown, uploads in progress get
  up to 30 s to finish.

---

## Behaviour

| Requirement | How |
|---|---|
| Only matching files | `include=RAW((?-i)regex)` on the file consumer. The whole name must match, case-sensitively. Files that match no transfer are left untouched. |
| Not while still being written | `readLock=changed`: the file's size and timestamp must stay unchanged for `readLockCheckInterval`. |
| Never sent twice | Idempotent consumer with a persistent `FileIdempotentRepository` per transfer, keyed on name + size + modified time. The key is stored only after a successful upload; failed files are not recorded. A new version of a file with the same name *is* sent. |
| No partial files on the server | Uploaded as `<name>.part`, then renamed to `<name>`. An existing file with that name is replaced only at the rename. |
| Retries, then error folder | Route `onException`: `maxRetries` retries, `retryDelay` apart (Camel's own reconnect loop is off). Then one ERROR line with the reason, and the file moves to `errorDir`. |
| One host must not block others | One route per transfer, each with its own polling thread and its own SFTP connection. A hanging or unreachable host only delays its own transfer. |
| Host key verification | `knownHostsFile` + `strictHostKeyChecking=yes` by default. Unknown or changed keys are rejected. No silent fallback to `~/.ssh/known_hosts`. |
| Logging, no secrets | One line per pickup, success and failure, with route, file, host, remote folder and result. Credentials only ever travel inside Camel's masked `RAW()` URI options. |

Log lines (logger `sftp.transfer`):

```
INFO  Uploading: route=bankA-payments file=PAY_20261126_001.csv (28 bytes) host=127.0.0.1:2222 remoteDir=/inbound/payments/
INFO  Transfer OK: route=bankA-payments file=PAY_20261126_001.csv host=127.0.0.1:2222 remoteDir=/inbound/payments/ result=SUCCESS
ERROR Transfer FAILED: route=bankA-wrong-hostkey file=PAY_mitm.csv host=127.0.0.1:51159 remoteDir=/inbound/payments/ result=FAILED after 3 retries, moved to .../mitm/error - reason: Cannot connect to sftp://userA@127.0.0.1:51159 (HostKey has been changed: [127.0.0.1]:51159)
```

**Sending a file again on purpose:** give it a new name or timestamp, or delete its line from
`<idempotentDir>/<name>-uploaded.txt`.

---

## Tests

`mvn verify` runs everything (about 40 s), with no external services needed.

`SftpTransferIntegrationTest` runs the real application against two embedded **Apache MINA SSHD** servers. Host A
uses key auth with an encrypted key and has two remote folders fed from one source folder. Host B uses password auth,
with a password full of URI-special characters. A third "host" accepts TCP connections and never answers. Sessions
start in `/home/<user>`, like on a real server, so absolute and relative remote folders can be told apart. The tests
check that:

* matching files arrive in the right host/folder only, with identical content, and the originals move to `done/`;
* data goes only to `<name>.part`, then gets renamed (taken from the server's own write and rename events);
* non-matching files (wrong extension, prefix, case, suffix) are ignored and left in place;
* a failed upload (missing remote folder) is retried, logged and moved to `error/`; nothing is created remotely;
* a server whose host key does not match known_hosts is refused and the file goes to `error/`;
* a hanging host does not delay other transfers, and it gets exactly 1 + 3 connection attempts before `error/`;
* the same file reappearing is not uploaded again;
* a file still being written is not picked up early;
* an absolute `remoteDir` is absolute and a relative one is relative to the login folder;
* passwords and passphrases never appear in the log.

`ConfigValidationTest` covers the fail-fast rules, the exact message an operator sees, and the glob translation.

---

## Notes and limitations

* **Patterns on a shared source folder must not overlap.** Each file should belong to exactly one transfer. Exact
  duplicates (same folder + same pattern) are rejected at startup; partial overlaps cannot be detected.
* **Empty (0-byte) files are not picked up** (Camel's read lock requires at least 1 byte); they stay in the folder.
* **Archive name clashes:** a file is moved to the done (backup) or error folder under its own name, replacing an older
  file with that name, for example when a corrected file with the same name is sent again.
* **Configuration changes need a restart.**
* A password, passphrase, user name or pattern must not contain the two-character sequence `)&`. That is the one
  thing Camel's `RAW()` syntax cannot carry; startup rejects it with a clear message.
* Very old servers that only offer `ssh-rsa` (SHA-1) host keys are refused by the SSH library (JSch) by default.
  Allowing them means adding `serverHostKeys=...` to the endpoint in the template.

## Project layout

```
pom.xml
src/main/java/com/example/multihostsftp/
    SftpTransferApplication.java       main class
    SftpTransferProperties.java        config binding + validation
    TransferRoutesBootstrap.java       one route per config entry
src/main/resources/
    application.yml                    which files go where
    camel/sftp-transfer-template.xml   the route template
src/test/java/com/example/multihostsftp/
    SftpTransferIntegrationTest.java   end-to-end against embedded SFTP servers
    ConfigValidationTest.java          fail-fast configuration rules
    LocalDemoApplication.java          mvn spring-boot:test-run
    support/                           EmbeddedSftpServer, BlackHoleServer, TestKeys
src/test/resources/
    application-it.yml                 integration test transfers
    application-local.yml              local demo transfers
```
