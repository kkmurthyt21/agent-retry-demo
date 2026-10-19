# Rehearsal checklist

The software verification is complete. This checklist records the presenter and laptop checks still to perform. Mark only checks actually completed.

## Today: one complete rehearsal

- [ ] Open the deck in the presentation application used on the laptop. Check fonts, notes, hidden appendices, diagrams, and presenter view.
- [ ] Run `java -version`. Use a compatible local Java runtime, with Java 17 as the tested baseline.
- [ ] Extract the complete kit and use a writable local directory. Do not run from inside the ZIP or a cloud-only placeholder.
- [ ] Reset and preflight the synthetic demo offstage. Confirm zero instructions.
- [ ] Disconnect the laptop from the internet and run the four commands in order. Confirm exits 0, 0, 23, 0 and final counts 1 per workflow, 2 total, 0 added during recovery.
- [ ] Verify the backup MP4 plays locally, including readable text. It has captions and no audio. Practice narrating one chapter.
- [ ] Rehearse the entire session with a timer, including terminal/deck transitions and eight minutes reserved for questions.
- [ ] Answer the first eight Q&A questions aloud. Keep each initial answer within one minute.

## Timing checkpoints

| Elapsed | Checkpoint |
|---|---|
| 03:00 | Failure story established; move to role responsibilities |
| 10:00 | Contract and version sections finished |
| 19:00 | Commit/acknowledgement gap explained |
| 25:30 | Recovery decisions complete |
| 27:00 | Begin the four-command demonstration |
| 34:00 | Return to recovery invariants |
| 37:00 | Begin Q&A |
| 45:00 | Finish |

If concepts run long, shorten the detailed version discussion and individual contract-field explanation. Preserve the crash/recovery demonstration, the three-minute conclusion, and the Q&A boundary. Do not rush through extra implementation details.

## October 23: freeze and copy

- [ ] Keep the exact deck, JAR, transcript, and backup playback together. Record their hashes after any changes.
- [ ] Re-run the final four-command sequence after any Java changes.
- [ ] Put a second copy on a USB drive or another local device.
- [ ] Confirm the route, arrival plan, registration, and any updated organizer schedule.
- [ ] Pack the laptop charger and required display adapters. Confirm the laptop can use the venue’s HDMI connection.

## October 24: at the venue

- [ ] Check in by 2:15 PM at the latest for the scheduled 2:45 PM session. Earlier arrival leaves more A/V time.
- [ ] Confirm Room 318 and the current end time with the organizer.
- [ ] Test projection, microphone, terminal size, display scaling, and presenter view.
- [ ] Open the deck, terminal, backup video, and static transcript before the talk.
- [ ] Reset/preflight the demo. Keep the laptop awake and notifications quiet.
- [ ] Begin with the failure story. Announce the intentional crash before running it.
- [ ] Finish at 3:30 PM. Preserve the delivered artifact versions afterward.

The venue checks and the presenter’s full timed rehearsal cannot be completed remotely. Keep their status visible rather than treating successful software tests as a completed live rehearsal.
