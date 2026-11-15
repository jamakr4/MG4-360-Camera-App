# MG4Control surveillance recording

Surveillance is a temporary recording session, limited to two hours. It runs in the existing
camera foreground service, with a persistent notification, without opening the app. Normal
dashcam recording is temporarily disabled; its previous toggle is restored on STOP, timeout,
or failure. If it was enabled, normal recording resumes after the surveillance writer and event
copies have finished. Shutdown and USB eject restore the toggle without reopening cameras.

## Commands

Package: `com.drivehub.kamera`. All four receivers are exported without a signature permission.
Their labels are localized in English and German. Each filter also contains
`com.mg4.control.action.SHORTCUT`, for MG4Control's **Custom shortcuts → App function** discovery.
The discovery action itself does nothing. MG4Control sends the command action to an explicit
receiver component; no extras are needed for the defaults.

| Action | Component |
| --- | --- |
| `com.drivehub.kamera.action.START_CONTINUOUS` | `com.drivehub.kamera/.remote.StartContinuousReceiver` |
| `com.drivehub.kamera.action.STOP_CONTINUOUS` | `com.drivehub.kamera/.remote.StopContinuousReceiver` |
| `com.drivehub.kamera.action.QUERY_STATE` | `com.drivehub.kamera/.remote.QueryStateReceiver` |
| `com.drivehub.kamera.action.TEST_USB_WRITE` | `com.drivehub.kamera/.remote.TestUsbWriteReceiver` |

START accepts **string** extras, including `max_minutes`. This matches MG4Control's Broadcast
shortcut type. Numeric Android extras are not required.

| Extra | Meaning / default |
| --- | --- |
| `max_minutes` | Trimmed positive integer; missing, malformed, nonpositive, or overflowing values use 120. Larger values are capped at 120. |
| `directory` | Absolute filesystem directory for this session. Empty uses the saved surveillance folder, then the automatic folder. |
| `storage_policy` | `recycle` (default), `stop`, or `warn`. Empty uses the default; an unknown nonempty value returns `invalid_storage_policy`. |
| `request_id` | Optional string echoed in session updates. STOP, QUERY_STATE, and the USB test also accept this extra. |

Repeated START returns the current state without changing the deadline, folder, policy, or
remembered toggle. STOP while idle returns `stopped` / `idle` and does not stop a running normal
dashcam or test clip. START during a test clip, future-only event capture, or USB eject returns
`recorder_busy`. Normal dashcam controls and test recording cannot take over an active session.
Event saves continue during surveillance; requests during its preparation or termination are ignored.

The `max_minutes` deadline starts when the recording service accepts a new START command, before
storage preparation or the first written sample. Startup time and OEM camera pauses count towards
that deadline. A repeated START or a status query never resets or extends it.

### Current-state query

Send `com.drivehub.kamera.action.QUERY_STATE` to
`com.drivehub.kamera/.remote.QueryStateReceiver`, with an optional string `request_id`.
It immediately replies with the existing `com.drivehub.kamera.action.RECORDING_STATE` broadcast
and its usual `state`, `reason`, `directory`, `free_bytes`, and `storage_level` extras. A supplied
query ID is echoed exactly; when omitted, the query response has no `request_id` extra.

QUERY_STATE is read-only: it does not start/recover a service, open cameras, change settings, or
alter the session deadline. Register the result receiver before querying after MG4Control restarts.
A live surveillance session returns its current observed recording state and latest cached free-space
measurement. The cache is normally refreshed about once per second; the query performs no USB write
test or filesystem-space probe. Normal dashcam recording alone returns `stopped` / `idle`.

When no surveillance session is active, the response is `stopped` / `idle`, with an empty directory
and `free_bytes=-1`. If a descriptor remains after the recording process stopped, the response is
`stopped` / `service_not_running`, with the stored directory where readable and `free_bytes=-1`.
Persisted flags never cause a false `started` reply; normal service recovery remains independent
of the query. Queries also leave malformed descriptors untouched for that recovery path.

```sh
# Default: two hours, automatic/saved separate folder, recycle when necessary.
adb shell am broadcast \
  -n com.drivehub.kamera/.remote.StartContinuousReceiver \
  -a com.drivehub.kamera.action.START_CONTINUOUS

# A shorter session with an explicit USB folder and preservation policy.
adb shell am broadcast \
  -n com.drivehub.kamera/.remote.StartContinuousReceiver \
  -a com.drivehub.kamera.action.START_CONTINUOUS \
  --es max_minutes 30 \
  --es directory /storage/ABCD-1234/surveillance \
  --es storage_policy stop \
  --es request_id keeper-1

adb shell am broadcast \
  -n com.drivehub.kamera/.remote.StopContinuousReceiver \
  -a com.drivehub.kamera.action.STOP_CONTINUOUS

adb shell am broadcast \
  -n com.drivehub.kamera/.remote.QueryStateReceiver \
  -a com.drivehub.kamera.action.QUERY_STATE \
  --es request_id state-query-1

adb shell am broadcast \
  -n com.drivehub.kamera/.remote.TestUsbWriteReceiver \
  -a com.drivehub.kamera.action.TEST_USB_WRITE \
  --es request_id usb-check-1
```

## Directories and storage

Choose a persistent folder under **Settings → Dashcam → Surveillance recording**. The picker
also offers **Use default folder**. A START `directory` overrides that preference for one session.
The automatic folder is named `surveillance`, beside the normal dashcam folder on the selected
storage target. The resolved destination is pinned for the session; losing it stops surveillance.
Its mount/device identity is checked so an unplugged drive is not silently replaced by internal storage.

Folders equal to, inside, or containing a normal dashcam root are rejected. The checks cover
configured/default/private roots, selected USB folders, symlinks, and `/storage/<volume>` versus
`/mnt/media_rw/<volume>` aliases. The latter writable native alias may be returned as the actual
destination on the car. Existing normal dashcam storage policy is restored afterwards.

Clips use `surv_<17-digit timestamp>_<8-character ID>.mp4`. A live segment has the suffix
`.mp4.part`, and is renamed after finalization and container validation. After process death,
readable partial containers are recovered; unreadable owned partials are discarded.

There is **no fixed size or clip-count cap** on surveillance recordings. At each segment boundary:

| Policy | Behavior |
| --- | --- |
| `recycle` | Reclaim oldest completed surveillance clips, including earlier sessions, until at least 1 GiB + 64 MiB is free. |
| `stop` | Stop before starting another segment if that reserve is unavailable. Keep all clips. |
| `warn` | Report space levels and continue without proactive deletion or a space-based stop. Stop on a recording/write failure. |

Recycling never deletes normal dashcam clips, unrelated files, active containers, event folders,
or event-protected source clips. Storage discovery does not require a successful write test first:
an already-full drive can reclaim its existing surveillance clips before probing. Every deletion
uses the existing truncate/fsync/unlink and directory-sync path, followed by another free-space
reading. Failed deletion, unavailable measurements, or no confirmed increase stops surveillance
instead of trying to delete the remaining archive. If no eligible clips can satisfy the reserve,
surveillance stops with `low_storage`.

Free space is checked at segment boundaries and approximately once per second while recording.
Notifications and state broadcasts report these levels immediately and on level transitions:

| `storage_level` | Available space |
| --- | --- |
| `very_low` | Below 1 GiB |
| `low` | At least 1 GiB, below 3 GiB |
| `limited` | At least 3 GiB, below 9 GiB |
| `ok` | At least 9 GiB |
| `unknown` | Measurement unavailable (`free_bytes=-1`) |

The 9 GiB threshold is a capacity estimate for two hours at the current bitrate, not a guarantee.
OEM pauses count towards the original deadline. Repeated START and process recovery never extend it.

## Results

Results are ordinary broadcasts for external dynamically registered receivers. They are not limited
to this app's package and require no signature permission. MG4Control currently does not consume them.

`com.drivehub.kamera.action.RECORDING_STATE`:

| Extra | Type / meaning |
| --- | --- |
| `state` | String: `starting`, `started`, `paused`, `stopped`, or `error` |
| `reason` | Machine-readable string; empty for ordinary progress |
| `directory` | Resolved destination, or requested/empty destination before preparation |
| `free_bytes` | Long; `-1` when unknown |
| `storage_level` | String from the table above |
| `request_id` | Optional string from START; a correlated terminal reply is also sent for STOP when it supplies a different ID. A QUERY_STATE reply uses only that query's optional ID. |

`started` requires actual written encoder samples. `paused` / `oem_camera` means the factory
camera app owns the cameras. Ordinary segment rotations do not emit a false stop/start cycle.
Reasons include `requested`, `timeout`, `idle`, `reboot`, `interrupted`, `shutdown`, `usb_eject`,
`service_not_running` (QUERY_STATE only), `foreground_start_rejected`, `recorder_busy`, `invalid_directory`, `directory_overlaps_dashcam`,
`invalid_storage_policy`, `no_medium`, `multiple_media`, `storage_unavailable`,
`storage_not_writable`, `storage_write_test_failed`, `storage_status_unavailable`, `low_storage`,
`storage_reclaim_failed`, `storage_reclaim_unconfirmed`, `camera_start_failed`, `recording_failed`,
`event_copy_timeout`, `event_copy_failed`, `session_persist_failed`, and `restore_failed`.

`com.drivehub.kamera.action.USB_WRITE_TEST_RESULT`:

| Extra | Type / meaning |
| --- | --- |
| `status` | String: `ok`, `no_medium`, `not_writable`, `write_test_failed`, `out_of_space`, `multiple_media`, `busy`, `timeout`, or `error` |
| `writable` | Boolean; true only after write/fsync/readback succeeds |
| `free_bytes`, `total_bytes` | Longs; `-1` when unavailable |
| `directory` | Tested native directory, or diagnostic volume path if directory creation failed; empty if no unambiguous drive |
| `storage_level` | String from the table above |
| `request_id` | Optional request correlation string |

The USB test ignores normal recording's Internal-only setting, respects the selected USB drive,
never deletes recordings or changes settings, and uses a unique temporary probe file. It is a
writability test, not a throughput benchmark. One result is returned within an eight-second
budget; overlapping requests or an eject/shutdown gate return `busy`. A blocked filesystem
operation remains tracked after the response timeout and cannot declare USB safe to remove.

The durable session descriptor includes the original toggle, destination/device identity,
storage policy, monotonic deadline, boot count, and terminal STOP request. A same-boot process
restart resumes only an unexpired, nonterminal session. A reboot, expired session, or malformed
descriptor restores normal settings. Android 9/SWI133 is the vehicle acceptance target; newer
Android background camera/foreground-service restrictions return a failure callback when enforced.

## Vehicle acceptance still required

- Keep MG4Control's warning screen foreground for a two-hour recording; verify notification,
  playable segments, final partial clip, deadline, and original normal dashcam restoration.
- Test START/STOP repetitions and immediate START→STOP, with normal dashcam initially on and off.
- Test OEM reverse/360 pauses, events, camera busy, process death, reboot, shutdown, and USB eject.
- Test low/full/read-only/unplugged USB, both mount aliases, and each storage policy. Confirm
  FAT free-space recovery and preservation of normal clips, saved events, and unrelated files.

Local checks cover pure policies/session ordering and isolated Android receiver/manifest/persistence
behavior. They do not exercise the vehicle's V4L2 hardware or prove two-hour recording reliability.

Validation on 8 November 2026: 83 JVM tests and 18 isolated Android instrumentation tests passed;
debug and release builds passed; lint reported 0 errors and 215 warnings. The connected Gradle
runner required uncached offline dependencies, so the compiled instrumentation APK was executed
directly with `adb shell am instrument`. The original emulator APK was restored afterwards.
The test APK uses the existing platform key/certificate and is verified for Android API 28+.
QUERY_STATE coverage includes live state, pending failure, and storage replies, correlation, unchanged deadlines and
preferences, idle/normal-dashcam behavior, and stopped/corrupt persisted sessions without recovery.
