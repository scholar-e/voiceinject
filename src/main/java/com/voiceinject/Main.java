//TODO: Add an icon when recording is on

package com.voiceinject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

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
		/** Tap to record, tap to preview, then tap to send the selected alternative. */
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
		public static KeyMapping cycleAlternative;

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

			cycleAlternative = KeyMappingHelper.registerKeyMapping(new KeyMapping(
					"key.voiceinject.cycle_alternative",
					InputConstants.Type.KEYSYM,
					InputConstants.KEY_B,
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
		private final AtomicInteger session = new AtomicInteger();
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private volatile Thread captureThread;
		private volatile TargetDataLine line;
		private volatile float sampleRate = 16000f;

		public boolean isRecording() {
			return recording.get();
		}

		public float sampleRate() {
			return sampleRate;
		}

		public void start() {
			if (!recording.compareAndSet(false, true)) {
				return;
			}
			int id = session.incrementAndGet();
			synchronized (buffer) {
				buffer.reset();
			}
			LOGGER.debug("Microphone start");
			Thread thread = new Thread(() -> captureLoop(id), "voiceinject-mic");
			thread.setDaemon(true);
			captureThread = thread;
			thread.start();
		}

		public byte[] stop() {
			if (!recording.compareAndSet(true, false)) {
				return new byte[0];
			}
			TargetDataLine toClose;
			synchronized (session) {
				session.incrementAndGet();
				toClose = line;
				line = null;
			}
			if (toClose != null) {
				closeQuietly(toClose);
			}
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

		private void captureLoop(int id) {
			TargetDataLine opened = null;
			boolean started = false;
			try {
				opened = openLine();
				if (opened == null) {
					LOGGER.error("No supported microphone line (tried 16/48/44.1 kHz 16-bit mono)");
					return;
				}
				if (!publishLine(id, opened)) {
					return;
				}
				sampleRate = opened.getFormat().getSampleRate();
				opened.start();
				started = true;
				//DEBUG
				debugChat("Microphone activated");
				LOGGER.debug("Microphone line started ({})", opened.getFormat());

				int maxBytes = maxBytesFor(opened.getFormat());
				byte[] chunk = new byte[READ_CHUNK];
				while (recording.get() && session.get() == id) {
					int n = opened.read(chunk, 0, chunk.length);
					if (n <= 0) {
						break;
					}
					synchronized (buffer) {
						if (session.get() != id || buffer.size() >= maxBytes) {
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
				if (started) {
					//DEBUG
					debugChat("Microphone deactivated");
				}
				synchronized (session) {
					if (line == opened) {
						line = null;
					}
				}
			}
		}

		private boolean publishLine(int id, TargetDataLine opened) {
			synchronized (session) {
				if (session.get() != id) {
					return false;
				}
				line = opened;
				return true;
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

		private static void debugChat(String message) {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> {
				if (client.player != null) {
					client.player.sendSystemMessage(Component.literal(message));
				}
			});
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
		private static final float TARGET_RATE = 16000f;
		private static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
		private static final URI MODEL_URL = URI.create("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip");

		private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "voiceinject-stt");
			thread.setDaemon(true);
			return thread;
		});

		private Model model;

		public CompletableFuture<List<String>> transcribe(byte[] pcm, float sampleRate) {
			return CompletableFuture.supplyAsync(() -> transcribeBlocking(pcm, sampleRate), executor);
		}

		private List<String> transcribeBlocking(byte[] pcm, float sampleRate) {
			if (pcm.length == 0) {
				return List.of();
			}
			try {
				byte[] sixteenKhz = to16kHz(pcm, sampleRate);
				if (sixteenKhz.length == 0) {
					return List.of();
				}
				Model loaded = ensureModel();
				try (Recognizer recognizer = new Recognizer(loaded, TARGET_RATE)) {
					recognizer.setMaxAlternatives(10);
					recognizer.acceptWaveForm(sixteenKhz, sixteenKhz.length);
					List<String> text = extractAlternatives(recognizer.getFinalResult());
					LOGGER.debug("Vosk result: {}", text);
					return text;
				}
			} catch (Throwable t) {
				LOGGER.error("Speech-to-text failed", t);
				return List.of();
			}
		}

		private Model ensureModel() throws IOException, InterruptedException {
			if (model != null) {
				return model;
			}
			LibVosk.setLogLevel(LogLevel.WARNINGS);
			Path modelDir = ensureModelOnDisk();
			LOGGER.info("Loading Vosk model from {}", modelDir);
			model = new Model(modelDir.toString());
			return model;
		}

		private static Path ensureModelOnDisk() throws IOException, InterruptedException {
			Path modelDir = FabricLoader.getInstance().getConfigDir().resolve("voiceinject").resolve(MODEL_NAME);
			if (isModelReady(modelDir)) {
				return modelDir;
			}
			if (Files.exists(modelDir)) {
				deleteRecursively(modelDir);
			}
			Path parent = modelDir.getParent();
			Files.createDirectories(parent);
			LOGGER.info("Downloading Vosk model from {}", MODEL_URL);
			Path zip = Files.createTempFile("voiceinject-vosk-", ".zip");
			try {
				download(MODEL_URL, zip);
				unzip(zip, parent);
			} finally {
				Files.deleteIfExists(zip);
			}
			if (!isModelReady(modelDir)) {
				throw new IOException("Vosk model missing after extract: " + modelDir);
			}
			return modelDir;
		}

		private static boolean isModelReady(Path modelDir) {
			return Files.isRegularFile(modelDir.resolve("conf").resolve("model.conf"))
					|| Files.isRegularFile(modelDir.resolve("am").resolve("final.mdl"));
		}

		private static void download(URI uri, Path dest) throws IOException, InterruptedException {
			HttpClient client = HttpClient.newBuilder()
					.followRedirects(HttpClient.Redirect.NORMAL)
					.connectTimeout(Duration.ofSeconds(30))
					.build();
			HttpRequest request = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofMinutes(5))
					.GET()
					.build();
			HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(dest));
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IOException("Download failed: HTTP " + status);
			}
		}

		private static void unzip(Path zipFile, Path destDir) throws IOException {
			Path destAbs = destDir.toAbsolutePath().normalize();
			try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zis = new ZipInputStream(in)) {
				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					Path out = destAbs.resolve(entry.getName()).normalize();
					if (!out.startsWith(destAbs)) {
						throw new IOException("Zip entry outside target: " + entry.getName());
					}
					if (entry.isDirectory()) {
						Files.createDirectories(out);
					} else {
						Files.createDirectories(out.getParent());
						Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
					}
					zis.closeEntry();
				}
			}
		}

		private static void deleteRecursively(Path root) throws IOException {
			if (!Files.exists(root)) {
				return;
			}
			try (Stream<Path> walk = Files.walk(root)) {
				walk.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
			} catch (RuntimeException e) {
				if (e.getCause() instanceof IOException io) {
					throw io;
				}
				throw e;
			}
		}

		private static byte[] to16kHz(byte[] pcm, float fromRate) {
			if (pcm.length < 2 || fromRate <= 0f || Math.abs(fromRate - TARGET_RATE) < 1f) {
				return pcm;
			}
			int srcSamples = pcm.length / 2;
			int dstSamples = Math.max(1, (int) (srcSamples * (TARGET_RATE / fromRate)));
			byte[] out = new byte[dstSamples * 2];
			double step = fromRate / TARGET_RATE;
			for (int i = 0; i < dstSamples; i++) {
				double srcIndex = i * step;
				int i0 = Math.min((int) srcIndex, srcSamples - 1);
				int i1 = Math.min(i0 + 1, srcSamples - 1);
				double t = srcIndex - i0;
				int s0 = readLe16(pcm, i0);
				int s1 = readLe16(pcm, i1);
				int sample = (int) Math.round(s0 + (s1 - s0) * t);
				if (sample > Short.MAX_VALUE) {
					sample = Short.MAX_VALUE;
				} else if (sample < Short.MIN_VALUE) {
					sample = Short.MIN_VALUE;
				}
				writeLe16(out, i, sample);
			}
			return out;
		}

		private static int readLe16(byte[] pcm, int sampleIndex) {
			int i = sampleIndex * 2;
			return (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
		}

		private static void writeLe16(byte[] out, int sampleIndex, int sample) {
			int i = sampleIndex * 2;
			out[i] = (byte) sample;
			out[i + 1] = (byte) (sample >> 8);
		}

		static List<String> extractAlternatives(String json) {
			List<String> options = new ArrayList<>();
			if (json == null || json.isBlank()) return options;
			JsonElement element = JsonParser.parseString(json);
			if (!element.isJsonObject()) return options;
			JsonElement alternatives = element.getAsJsonObject().get("alternatives");
			if (alternatives != null && alternatives.isJsonArray()) {
				for (JsonElement alternative : alternatives.getAsJsonArray()) {
					addAlternative(options, alternative);
				}
			}
			if (options.isEmpty()) addAlternative(options, element);
			return List.copyOf(options);
		}

		private static void addAlternative(List<String> options, JsonElement element) {
			if (!element.isJsonObject()) return;
			JsonElement text = element.getAsJsonObject().get("text");
			if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) return;
			String value = text.getAsString().trim();
			if (!value.isEmpty() && !options.contains(value)) options.add(value);
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
		private List<String> pending = List.of();
		private int selected;
		private int session;
		private Object connection;

		public VoiceController(Config config, MicrophoneCapture microphone, SpeechToText speechToText, ChatInjector chat) {
			this.config = config;
			this.microphone = microphone;
			this.speechToText = speechToText;
			this.chat = chat;
		}

		public void tick(Minecraft client) {
			if (client.player == null || connection != client.player.connection) {
				connection = client.player == null ? null : client.player.connection;
				session++;
				sending = false;
				pending = List.of();
				holdWasDown = false;
				if (microphone.isRecording()) microphone.stop();
				drainClicks();
				return;
			}
			if (sending) {
				drainClicks();
				return;
			}

			while (Keybinds.cycleMode.consumeClick()) {
				config.cycleMode();
				pending = List.of();
				client.gui.hud.setOverlayMessage(Component.empty(), false);
				if (microphone.isRecording()) {
					microphone.stop();
				}
				holdWasDown = false;
				client.player.sendSystemMessage(
						Component.literal("voiceinject mode: " + config.mode.name().toLowerCase())
				);
			}

			while (Keybinds.cycleAlternative.consumeClick()) {
				if (!pending.isEmpty() && client.gui.screen() == null) {
					selected = (selected + 1) % pending.size();
				}
			}
			if (!pending.isEmpty()) showPreview(client);

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
				if (!pending.isEmpty()) {
					chat.send(client, pending.get(selected));
					pending = List.of();
					client.gui.hud.setOverlayMessage(Component.empty(), false);
					drainClicks();
					return;
				} else if (microphone.isRecording()) {
					finishSession(client);
					drainClicks();
					return;
				} else {
					microphone.start();
				}
			}
		}

		private void showPreview(Minecraft client) {
			client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.preview",
					selected + 1, pending.size(), pending.get(selected),
					Keybinds.cycleAlternative.getTranslatedKeyMessage(),
					Keybinds.record.getTranslatedKeyMessage()), false);
		}

		private void finishSession(Minecraft client) {
			float rate = microphone.sampleRate();
			byte[] pcm = microphone.stop();
			sending = true;
			int requestSession = session;
			boolean preview = config.mode == RecordingMode.TAP;
			if (preview) client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.transcribing"), false);
			speechToText.transcribe(pcm, rate).whenComplete((text, error) -> client.execute(() -> {
				if (requestSession != session || client.player == null || client.player.connection != connection) return;
				drainClicks();
				sending = false;
				if (error != null) {
					LOGGER.error("Speech-to-text failed", error);
					return;
				}
				if (text.isEmpty()) {
					client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.no_speech"), false);
				} else if (preview) {
					pending = text;
					selected = 0;
					showPreview(client);
				} else {
					chat.send(client, text.get(0));
				}
			}));
		}

		private static void drainClicks() {
			while (Keybinds.cycleAlternative.consumeClick()) {
			}
			while (Keybinds.record.consumeClick()) {
				// drop buffered presses while a send is in flight or the player is missing
			}
			while (Keybinds.cycleMode.consumeClick()) {
			}
		}
	}
}
