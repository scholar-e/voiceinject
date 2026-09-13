# VoiceInject

A Minecraft Fabric mod that converts microphone input to text and sends it to chat.

VoiceInject settings open automatically when Minecraft reaches its first screen after startup. The settings menu includes microphone selection and a **Configure keybinds** button. Press **O** in a world to reopen VoiceInject settings later and choose **Hold** or **Tap** mode. Settings are saved in `config/voiceinject.json`.

- **Hold:** hold **V** to record and release it to recognize and send.
- **Tap:** press **V** to start recording, then **V** to stop and preview. Press **B** to cycle through up to ten distinct predictions and a final **Discard message** option. Press **V** to confirm the selection; confirming Discard sends nothing.
- A red microphone indicator appears while recording. Previewing does not open chat or capture movement/combat controls. Opening settings cancels the current recording or preview.

On Linux, microphone capture uses PulseAudio/PipeWire and the selected source; other systems use Java Sound. The microphone opens when recording starts and closes when recording stops. Recordings are capped at 30 seconds.

Recognition uses the local **vosk-model-en-us-0.22-lgraph** model (128 MB download). On first transcription it downloads automatically; the previous small model is deleted from the mod's config folder only after lgraph loads successfully. Recognition may produce fewer than ten distinct predictions. Empty results prompt you to record again.

Build with Java 25: `bash gradlew build`. The build includes regression checks for pre-roll ordering, tail inclusion, discard, and the recording length cap.

## Hot commands

Open **O → Hot commands** to save up to five commands. Leave a slot blank to remove it; **Save** applies changes, while **Cancel** or Escape discards edits.

- **C** cycles through saved commands and **Normal chat**, without sending anything. Selection resets on disconnect or opening settings.
- For voice messages, save `/msg Alex {text}`. Record as usual and the recognized words replace `{text}`. A command without `{text}`, such as `/msg Alex`, acts as a prefix when recording speech. Tap previews show the full command before confirmation.
- **H** sends the selected command exactly as saved, for shortcuts such as `/home`. Commands containing `{text}` require a voice recording instead. H is ignored during recording, transcription, or prediction review.
- Choose **Normal chat** with C to stop prefixing voice messages. Keybinds are configurable in Controls. Messages over 256 characters are rejected.
