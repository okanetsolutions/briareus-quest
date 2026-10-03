# Security

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately through GitHub's **Report a vulnerability** form on this repository's Security tab. You will get an acknowledgement within a few days and a fix or a timeline once the report is confirmed.

## Supported versions

Only the latest release receives fixes.

## What the app does with your data

- The device token is sealed with AES-256-GCM under a key generated inside the headset's Android Keystore, which never leaves it (hardware-backed where the headset has it). It is sent to your Briareus server over HTTPS and nowhere else. Plain HTTP, redirects, user-installed certificate authorities and TLS below 1.2 are refused.
- Saved responses (projects, conversations, transcripts) live in the app's no-backup directory, sealed with the same key, and are erased when the connection is forgotten, revoked or replaced. Forgetting also deletes the key, so anything left behind cannot be read. Entries untouched for 30 days are dropped.
- Nothing is backed up or transferred to another device: backup and device transfer exclude every file.
- Replies typed in a notification go to your server as a message in that conversation.
- Voice notes are sent to your server for transcription and to no other service, and the recording is deleted from the headset once read.
- The app has no analytics, telemetry or automatic updates.
