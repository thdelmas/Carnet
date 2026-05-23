# Carnet — manifesto

> *Carnet de laboratoire — a personal record, structured and unembellished.*

## What Carnet is

A scientific-instrument camera app for self-as-subject documentation. The user records longitudinal video of themselves on a defined cadence (weekly default, with monthly and quarterly extensions per the [video framework](https://github.com/mi4m/miam-knowledge-base/blob/main/docs/life/video-weekly-framework.md)). Each recording is timestamped, fingerprinted with a session UID, and accompanied by a snapshot of the owner's current Bios vitals.

The app is an instrument. The user is the subject. The recording is the data.

## What Carnet is not

- **Not a content creator's toolkit.** No filters, no music overlays, no aspect-ratio tricks. The HUD is the only on-screen element added to the recording, and it shows metadata, not branding.
- **Not a coach.** The app does not evaluate the recording's content, score the user, or surface insights about the footage. Evaluation belongs to the subject; the app's job is to capture, not to judge. (Same posture as Bios.)
- **Not an audience platform.** Nothing in Carnet routes to a public surface. If the subject decides a recording becomes public, that's an external decision made outside the app.

## Principles

1. **The instrument is opinionated; the subject is sovereign.** The format (vertical, fixed HUD, structured filename, sidecar metadata) is decided by the app. What gets said, who watches it, whether it's ever shown — decided by the subject.
2. **Faithfulness over flattery.** No exposure tricks that lie about the subject's appearance. No noise reduction that smooths away grain that's part of the longitudinal signal. The recording should look like the subject looked.
3. **Metadata is load-bearing.** A recording without its sidecar (subject, session, UID, timestamps, Bios snapshot) is half-data. Sidecars travel with their video; deletion of one without the other is treated as a corruption.
4. **Bios is the spine.** Carnet writes to and reads from Bios via the standard companion-app interface. Recording events appear in the Bios timeline; vitals at recording start appear in the Carnet sidecar. Two halves of one instrument.
5. **Local-first, deletable.** Recordings live on the device. The subject can delete any session at any time — both the video and the sidecar. No remote backup decisions are made without explicit consent (Bios manifesto inheritance).

## What Carnet measures

Carnet doesn't measure the subject. Carnet measures **the act of subject documentation** — the cadence of recordings, their durations, their session UIDs, the gaps between them. Bios measures the body; Carnet measures the discipline of looking at the body.

A gap of three weeks between recordings is data. A streak of fifteen consecutive Sundays is data. The decision to record at 06:30 vs 23:00 is data. The instrument records all of it without comment.

## Decision frame

Inherits the [universal infra serves conditions](https://github.com/mi4m/miam-knowledge-base/) memory: Carnet is universal subject-documentation infrastructure. The owner happens to be the first subject; the design must hold for any subject who wants to instrument their own longitudinal video archive.
