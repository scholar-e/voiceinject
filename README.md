# VoiceInject

A Minecraft Fabric mod that converts microphone input to text and sends it to chat.

Use either hold-to-talk or tap-to-toggle recording.

In tap mode:
1. Press **V** to start recording, then **V** again to stop and recognize speech.
2. Review the selected prediction above the hotbar. Press **B** to cycle through up to ten distinct predictions (wrapping back to the first).
3. Press **V** again to send the selected prediction. Your next press starts a new recording.

These keys can be changed in Minecraft Controls. Bind **Cycle hold/tap mode** there to switch modes; the default mode is hold. Switching modes discards a pending preview. Hold mode sends immediately on release. Previewing does not open chat or capture movement/combat controls. Recognition may return fewer than ten predictions; empty results prompt you to record again.
