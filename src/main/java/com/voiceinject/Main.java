package com.voiceinject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client entrypoint for voiceinject.
 *
 * Pipeline:
 *   keybind (hold or tap) -> microphone capture -> speech-to-text -> chatbox
 */
public final class Main implements ClientModInitializer {
	public static final String MOD_ID = "voiceinject";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Config config;
	private static VoiceController controller;

	@Override
	public void onInitializeClient() {
		config = Config.load();
		Keybinds.register();

		controller = new VoiceController(
				config,
				new MicrophoneCapture(),
				new SpeechToText(),
				new ChatInjector()
		);

		ClientTickEvents.END_CLIENT_TICK.register(controller::tick);
		LOGGER.info("Initialized (mode={})", config.mode);
	}

	public enum RecordingMode {
		/** Hold to record, release to transcribe and send. */
		HOLD,
		/** Tap to start recording, tap again to transcribe and send. */
		TAP
	}

	/** In-memory settings. Persistence / options screen can replace this later. */
	public static final class Config {
		public RecordingMode mode = RecordingMode.HOLD;

		public static Config load() {
			return new Config();
		}

		public void cycleMode() {
			mode = (mode == RecordingMode.HOLD) ? RecordingMode.TAP : RecordingMode.HOLD;
		}
	}

	public static final class Keybinds {
		public static KeyMapping.Category category;
		public static KeyMapping record;
		public static KeyMapping cycleMode;

		private Keybinds() {
		}

		public static void register() {
			category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "voice"));

			record = KeyMappingHelper.registerKeyMapping(new KeyMapping(
					"key.voiceinject.record",
					InputConstants.Type.KEYSYM,
					InputConstants.KEY_V,
					category
			));

			cycleMode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
					"key.voiceinject.cycle_mode",
					InputConstants.Type.KEYSYM,
					InputConstants.UNKNOWN.getValue(),
					category
			));
		}
	}

	/** Captures PCM from the default microphone while a session is active. */
	public static final class MicrophoneCapture {
		private static final float[] SAMPLE_RATES = { 16000f, 48000f, 44100f };
		private static final int SAMPLE_BITS = 16;
		private static final int CHANNELS = 1;
		private static final int MAX_SECONDS = 30;
		private static final int READ_CHUNK = 4096;
		private static final long JOIN_TIMEOUT_MS = 500L;

		private final AtomicBoolean recording = new AtomicBoolean(false);
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private volatile Thread captureThread;
		private volatile TargetDataLine line;

		public boolean isRecording() {
			return recording.get();
		}

		public void start() {
			if (!recording.compareAndSet(false, true)) {
				return;
			}
			synchronized (buffer) {
				buffer.reset();
			}
			LOGGER.debug("Microphone start");
			Thread thread = new Thread(this::captureLoop, "voiceinject-mic");
			thread.setDaemon(true);
			captureThread = thread;
			thread.start();
		}

		public byte[] stop() {
			if (!recording.compareAndSet(true, false)) {
				return new byte[0];
			}
			closeLine();
			Thread thread = captureThread;
			if (thread != null) {
				try {
					thread.join(JOIN_TIMEOUT_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			captureThread = null;
			byte[] pcm;
			synchronized (buffer) {
				pcm = buffer.toByteArray();
			}
			LOGGER.debug("Microphone stop ({} bytes)", pcm.length);
			return pcm;
		}

		private void captureLoop() {
			TargetDataLine opened = null;
			try {
				opened = openLine();
				if (opened == null) {
					LOGGER.error("No supported microphone line (tried 16/48/44.1 kHz 16-bit mono)");
					return;
				}
				line = opened;
				opened.start();
				LOGGER.debug("Microphone line started ({})", opened.getFormat());

				int maxBytes = maxBytesFor(opened.getFormat());
				byte[] chunk = new byte[READ_CHUNK];
				while (recording.get()) {
					int n = opened.read(chunk, 0, chunk.length);
					if (n <= 0) {
						break;
					}
					synchronized (buffer) {
						if (buffer.size() >= maxBytes) {
							break;
						}
						int toWrite = Math.min(n, maxBytes - buffer.size());
						buffer.write(chunk, 0, toWrite);
					}
				}
			} catch (LineUnavailableException e) {
				LOGGER.error("Microphone unavailable", e);
			} catch (SecurityException e) {
				LOGGER.error("Microphone permission denied", e);
			} catch (RuntimeException e) {
				LOGGER.error("Microphone capture failed", e);
			} finally {
				if (opened != null) {
					closeQuietly(opened);
				}
				if (line == opened) {
					line = null;
				}
			}
		}

		private static TargetDataLine openLine() throws LineUnavailableException {
			LineUnavailableException lastUnavailable = null;
			for (float sampleRate : SAMPLE_RATES) {
				AudioFormat format = pcmMonoLe(sampleRate);
				DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
				if (!AudioSystem.isLineSupported(info)) {
					continue;
				}
				try {
					TargetDataLine opened = (TargetDataLine) AudioSystem.getLine(info);
					opened.open(format);
					LOGGER.debug("Opened microphone at {} Hz", (int) sampleRate);
					return opened;
				} catch (LineUnavailableException e) {
					lastUnavailable = e;
					LOGGER.debug("Microphone {} Hz unavailable", (int) sampleRate, e);
				}
			}
			if (lastUnavailable != null) {
				throw lastUnavailable;
			}
			return null;
		}

		private static AudioFormat pcmMonoLe(float sampleRate) {
			return new AudioFormat(sampleRate, SAMPLE_BITS, CHANNELS, true, false);
		}

		private static int maxBytesFor(AudioFormat format) {
			int bytesPerSecond = (int) (format.getSampleRate() * (format.getSampleSizeInBits() / 8) * format.getChannels());
			return bytesPerSecond * MAX_SECONDS;
		}

		private void closeLine() {
			TargetDataLine current = line;
			if (current != null) {
				closeQuietly(current);
			}
		}

		private static void closeQuietly(TargetDataLine current) {
			try {
				current.stop();
			} catch (RuntimeException ignored) {
			}
			try {
				current.flush();
			} catch (RuntimeException ignored) {
			}
			try {
				current.close();
			} catch (RuntimeException ignored) {
			}
		}
	}

	/** Converts captured audio into chat text. */
	public static final class SpeechToText {
		private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "voiceinject-stt");
			thread.setDaemon(true);
			return thread;
		});

		public CompletableFuture<String> transcribe(byte[] pcm) {
			return CompletableFuture.supplyAsync(() -> transcribeBlocking(pcm), executor);
		}

		private String transcribeBlocking(byte[] pcm) {
			if (pcm.length == 0) {
				return "";
			}
			// TODO: run Whisper / Vosk / cloud STT against the PCM buffer
			return "";
		}
	}

	/** Sends recognized text as the local player. */
	public static final class ChatInjector {
		public void send(Minecraft client, String text) {
			if (client.player == null) {
				return;
			}

			String message = text == null ? "" : text.trim();
			if (message.isEmpty()) {
				return;
			}

			if (message.startsWith("/")) {
				client.player.connection.sendCommand(message.substring(1));
			} else {
				client.player.connection.sendChat(message);
			}
		}
	}

	/**
	 * Polls keybinds each client tick and drives a recording session.
	 *
	 * Hold: edge-trigger on press/release via {@link KeyMapping#isDown()}.
	 * Tap:  consume each press via {@link KeyMapping#consumeClick()}.
	 */
	public static final class VoiceController {
		private final Config config;
		private final MicrophoneCapture microphone;
		private final SpeechToText speechToText;
		private final ChatInjector chat;

		private boolean holdWasDown;
		private boolean sending;

		public VoiceController(Config config, MicrophoneCapture microphone, SpeechToText speechToText, ChatInjector chat) {
			this.config = config;
			this.microphone = microphone;
			this.speechToText = speechToText;
			this.chat = chat;
		}

		public void tick(Minecraft client) {
			if (client.player == null || sending) {
				drainClicks();
				return;
			}

			while (Keybinds.cycleMode.consumeClick()) {
				config.cycleMode();
				if (microphone.isRecording()) {
					microphone.stop();
				}
				holdWasDown = false;
				client.player.sendSystemMessage(
						Component.literal("voiceinject mode: " + config.mode.name().toLowerCase())
				);
			}

			if (config.mode == RecordingMode.HOLD) {
				tickHold(client);
			} else {
				tickTap(client);
			}
		}

		private void tickHold(Minecraft client) {
			boolean down = Keybinds.record.isDown() && client.gui.screen() == null;
			if (down && !holdWasDown) {
				microphone.start();
			} else if (!down && holdWasDown) {
				finishSession(client);
			}
			holdWasDown = down;
		}

		private void tickTap(Minecraft client) {
			while (Keybinds.record.consumeClick()) {
				if (client.gui.screen() != null) {
					continue;
				}
				if (microphone.isRecording()) {
					finishSession(client);
				} else {
					microphone.start();
				}
			}
		}

		private void finishSession(Minecraft client) {
			byte[] pcm = microphone.stop();
			sending = true;
			speechToText.transcribe(pcm).whenComplete((text, error) -> client.execute(() -> {
				sending = false;
				if (error != null) {
					LOGGER.error("Speech-to-text failed", error);
					return;
				}
				chat.send(client, text);
			}));
		}

		private static void drainClicks() {
			while (Keybinds.record.consumeClick()) {
				// drop buffered presses while a send is in flight or the player is missing
			}
			while (Keybinds.cycleMode.consumeClick()) {
			}
		}
	}
}
