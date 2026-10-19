# Backup recording and playback plan

The complete kit contains a seven-minute captioned backup playback assembled from the actual four-command output of the packaged Java application. It is labeled as captured execution playback. It has no audio. Narrate it using the demo script. It is preparation material, not a recording of the conference session.

## Files and timing

| File | Duration | Segment |
|---|---:|---|
| `backup/DevFest_Troy_2026_Demo_Backup.mp4` | 7:00 | Complete four-command playback |
| `backup/01-normal.mp4` | 1:15 | Normal Planner–Executor–Verifier flow |
| `backup/02-boundaries.mp4` | 1:45 | Duplicate event, same action, validation and policy rejection |
| `backup/03-crash.mp4` | 1:45 | Gateway commit, unfinished workflow, planned exit 23 |
| `backup/04-recover.mp4` | 2:15 | New process, recovered receipt, zero new instructions |
| `docs/DEMO_TRANSCRIPT.txt` | Read at your own pace | Exact captured commands, output, and exit codes |

The video holds readable output while captions identify the relevant boundary. Its duration includes speaking and explanation time; it is not a performance measurement of the commands. The shipped video preserves the October 18 capture. Its original JAR hash and exit codes are retained in `backup/CAPTURE_METADATA_2026-10-18.json`. The October 19 Java package correction was rebuilt and tested; `docs/CAPTURE_METADATA.json` and the transcript describe the current JAR. All four onstage command outputs were compared with the original capture and match exactly.

## Switching during the live session

Keep the full recording and the four chapters available locally. If the live run fails unexpectedly, spend at most 20 seconds stating the observed state, then play the matching chapter. Use only the remaining chapters so the demonstration still ends at minute 34. If video playback is unavailable, use the static transcript or slide 15 and explain the same counts and state transitions.

Do not reset databases, download dependencies, rebuild, or debug while presenting. The fallback is part of the prepared explanation.

## Optional laptop recording before departure

After rehearsing the stable release, record the same four commands on the presentation laptop with its normal terminal font. A voice-over can use the seven-minute script. Clearly announce the planned crash and show the receipt identity before and after restart. Keep the shipped captioned backup until the replacement is checked.

Verify the replacement offline from start to finish. Check legibility on an external display. Keep a second local copy, plus the static transcript. Preserve the original capture and release hash if a new recording is made.
