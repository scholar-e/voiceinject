package com.voiceinject;

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
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
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

		SpeechToText speechToText = new SpeechToText();
		controller = new VoiceController(
				config,
				new MicrophoneCapture(),
				speechToText,
				new ChatInjector()
		);
		// Download and load the 128 MB model in the background so the first recording
		// isn't blocked by a multi-minute download on the transcription thread.
		speechToText.preload();

		ClientTickEvents.END_CLIENT_TICK.register(controller::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> controller.microphone.close());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "recording"), (graphics, delta) -> {
			Minecraft client = Minecraft.getInstance();
			if (client.player != null && controller.microphone.isRecording() && !client.gui.hud.isHidden()) {
				// A small microphone silhouette, drawn without a texture dependency.
				graphics.fill(8, 8, 20, 26, 0xAA000000);
				graphics.fill(12, 10, 16, 18, 0xFFFF4545);
				graphics.fill(10, 15, 11, 20, 0xFFFFFFFF);
				graphics.fill(17, 15, 18, 20, 0xFFFFFFFF);
				graphics.fill(11, 20, 17, 21, 0xFFFFFFFF);
				graphics.fill(13, 21, 15, 24, 0xFFFFFFFF);
				graphics.fill(11, 24, 17, 25, 0xFFFFFFFF);
				graphics.text(client.font, Component.translatable("voiceinject.recording"), 24, 13, 0xFFFFFFFF);
			}
		});
		LOGGER.info("Initialized (mode={})", config.mode);
	}

	public enum RecordingMode {
		/** Hold to record, release to transcribe and send. */
		HOLD,
		/** Tap to record, tap to preview, then tap to send the selected alternative. */
		TAP
	}

	/** Settings saved in the instance config directory. */
	public static final class Config {
		public RecordingMode mode = RecordingMode.HOLD;
		public List<String> hotCommands = List.of();
		private static Path path() {
			return FabricLoader.getInstance().getConfigDir().resolve("voiceinject.json");
		}
		public static Config load() {
			Config result = new Config();
			if (Files.exists(path())) {
				try {
					JsonObject saved = JsonParser.parseString(Files.readString(path())).getAsJsonObject();
					if (saved.has("mode")) result.mode = RecordingMode.valueOf(saved.get("mode").getAsString());
					List<String> commands = new ArrayList<>();
					if (saved.has("hotCommands") && saved.get("hotCommands").isJsonArray()) {
						for (JsonElement entry : saved.getAsJsonArray("hotCommands")) {
							if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
							String value = entry.getAsString().trim();
							if (!value.isEmpty() && HotCommands.valid(value) && commands.size() < HotCommands.LIMIT) commands.add(value);
						}
					}
					result.hotCommands = List.copyOf(commands);
				} catch (Exception e) { LOGGER.warn("Could not load voice settings; using hold mode", e); }
			}
			return result;
		}
		public boolean save() {
			try {
				Files.createDirectories(path().getParent());
				Path temporary = Files.createTempFile(path().getParent(), "voiceinject-settings", ".tmp");
				try {
					JsonObject saved = new JsonObject();
					saved.addProperty("mode", mode.name());
					JsonArray commands = new JsonArray();
					hotCommands.forEach(commands::add);
					saved.add("hotCommands", commands);
					Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(saved) + "\n");
					Files.move(temporary, path(), StandardCopyOption.REPLACE_EXISTING);
				} finally { Files.deleteIfExists(temporary); }
				return true;
			} catch (IOException e) { LOGGER.error("Could not save voice settings", e); return false; }
		}
	}

	public static final class ConfigScreen extends Screen {
		private final Screen parent;
		private final Config settings;
		private boolean saveFailed;
		ConfigScreen(Screen parent, Config settings) {
			super(Component.translatable("voiceinject.settings"));
			this.parent = parent;
			this.settings = settings;
		}
		private Component modeLabel() {
			return Component.translatable("voiceinject.mode", Component.translatable("voiceinject.mode." + settings.mode.name().toLowerCase(java.util.Locale.ROOT)));
		}
		@Override protected void init() {
			addRenderableWidget(Button.builder(modeLabel(), button -> {
				settings.mode = settings.mode == RecordingMode.HOLD ? RecordingMode.TAP : RecordingMode.HOLD;
				saveFailed = !settings.save();
				button.setMessage(modeLabel());
			}).bounds(width / 2 - 100, height / 2 - 24, 200, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("voiceinject.hot.title"), button ->
					minecraft.gui.setScreen(new HotCommandScreen(this, settings)))
					.bounds(width / 2 - 100, height / 2 + 28, 200, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
					.bounds(width / 2 - 100, height / 2 + 50, 200, 20).build());
		}
		@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.centeredText(font, title, width / 2, height / 2 - 60, 0xFFFFFFFF);
			graphics.centeredText(font, Component.translatable("voiceinject.settings.help"), width / 2, height / 2 + 8, 0xFFCCCCCC);
			if (saveFailed) graphics.centeredText(font, Component.translatable("voiceinject.settings.save_failed"), width / 2, height / 2 + 76, 0xFFFF5555);
		}
		@Override public void onClose() { minecraft.gui.setScreen(parent); }
		@Override public boolean isPauseScreen() { return false; }
	}

	public static final class HotCommandScreen extends Screen {
		private final Screen parent;
		private final Config settings;
		private final List<String> drafts = new ArrayList<>();
		private Component error = Component.empty();
		HotCommandScreen(Screen parent, Config settings) {
			super(Component.translatable("voiceinject.hot.title"));
			this.parent = parent;
			this.settings = settings;
			for (int i = 0; i < HotCommands.LIMIT; i++) drafts.add(i < settings.hotCommands.size() ? settings.hotCommands.get(i) : "");
		}
		@Override protected void init() {
			int fieldWidth = Math.min(360, width - 32);
			int top = Math.max(40, height / 2 - 62);
			for (int i = 0; i < HotCommands.LIMIT; i++) {
				final int slot = i;
				EditBox field = new EditBox(font, (width - fieldWidth) / 2, top + i * 24, fieldWidth, 20,
						Component.translatable("voiceinject.hot.slot", i + 1));
				field.setMaxLength(256);
				field.setValue(drafts.get(i));
				field.setHint(Component.literal("/msg PlayerName {text}"));
				field.setResponder(value -> drafts.set(slot, value));
				addRenderableWidget(field);
			}
			addRenderableWidget(Button.builder(Component.translatable("voiceinject.hot.save"), button -> {
				List<String> values = drafts.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
				if (values.stream().anyMatch(value -> !HotCommands.valid(value))) {
					error = Component.translatable("voiceinject.hot.invalid");
					return;
				}
				List<String> previous = settings.hotCommands;
				settings.hotCommands = values;
				if (settings.save()) onClose();
				else { settings.hotCommands = previous; error = Component.translatable("voiceinject.settings.save_failed"); }
			}).bounds(width / 2 - 104, top + 124, 100, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
					.bounds(width / 2 + 4, top + 124, 100, 20).build());
		}
		@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
			graphics.centeredText(font, Component.translatable("voiceinject.hot.help"), width / 2, 24, 0xFFCCCCCC);
			graphics.centeredText(font, error, width / 2, Math.max(40, height / 2 - 62) + 148, 0xFFFF5555);
		}
		@Override public void onClose() { minecraft.gui.setScreen(parent); }
		@Override public boolean isPauseScreen() { return false; }
	}

	public static final class Keybinds {
		public static KeyMapping.Category category;
		public static KeyMapping record;
		public static KeyMapping openConfig;
		public static KeyMapping cycleAlternative;
		public static KeyMapping cycleHotCommand;
		public static KeyMapping sendHotCommand;

		private Keybinds() {
		}

		public static void register() {
			category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "voice"));
			cycleHotCommand = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.voiceinject.cycle_hot_command",
					InputConstants.Type.KEYSYM, InputConstants.KEY_C, category));
			sendHotCommand = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.voiceinject.send_hot_command",
					InputConstants.Type.KEYSYM, InputConstants.KEY_H, category));

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

			openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
					"key.voiceinject.open_config",
					InputConstants.Type.KEYSYM,
					InputConstants.KEY_O,
					category
			));
		}
	}

	/** Keeps 250 ms of local pre-roll and completes recordings after a 200 ms tail. */
	public static final class MicrophoneCapture {
		private final Object lock = new Object();
		private volatile boolean running;
		private volatile boolean ready;
		private volatile TargetDataLine line;
		private Thread worker;
		private int generation;
		private RecordingBuffer buffer;
		private boolean recording;
		private long stopAt;
		private float sampleRate = 16000f;
		private CompletableFuture<AudioClip> completion;

		public record AudioClip(byte[] pcm, float sampleRate) {}
		public boolean isRecording() { synchronized (lock) { return recording; } }
		public void warmUp() {
			synchronized (lock) {
				if (ready || running) return;
				int gen = ++generation;
				running = true;
				worker = new Thread(() -> captureLoop(gen), "voiceinject-mic");
				worker.setDaemon(true);
				worker.start();
			}
		}
		public boolean start() {
			synchronized (lock) {
				if (!ready || recording) return false;
				buffer.start();
				recording = true;
				stopAt = Long.MAX_VALUE;
				completion = new CompletableFuture<>();
				return true;
			}
		}
		public CompletableFuture<AudioClip> stop() {
			synchronized (lock) {
				if (completion == null) return CompletableFuture.completedFuture(new AudioClip(new byte[0], sampleRate));
				if (recording && stopAt == Long.MAX_VALUE) stopAt = System.nanoTime() + 200_000_000L;
				return completion;
			}
		}
		public boolean hasCompletedRecording() {
			synchronized (lock) { return completion != null && completion.isDone(); }
		}
		public void discard() {
			synchronized (lock) {
				recording = false;
				if (buffer != null) buffer.discard();
				if (completion != null && !completion.isDone()) completion.cancel(false);
				completion = null;
			}
		}
		public void close() {
			synchronized (lock) {
				generation++;
				running = false;
				ready = false;
			}
			discard();
			TargetDataLine current = line;
			if (current != null) current.close();
		}
		private void captureLoop(int gen) {
			TargetDataLine opened = null;
			try {
				opened = openLine();
				synchronized (lock) {
					if (gen != generation || !running) return;
					line = opened;
					sampleRate = opened.getFormat().getSampleRate();
					buffer = new RecordingBuffer((int) sampleRate / 4 * 2, (int) sampleRate * 2 * 30);
					opened.start();
					ready = true;
				}
				byte[] chunk = new byte[2048];
				while (true) {
					synchronized (lock) {
						if (gen != generation || !running) break;
					}
					// Read only available whole samples so stop never drops a blocked read.
					int count = Math.min(opened.available(), chunk.length) & ~1;
					if (count > 0) count = opened.read(chunk, 0, count);
					synchronized (lock) {
						if (gen != generation || !running) break;
						if (count > 0) buffer.append(chunk, count);
						if (recording && (buffer.full() || System.nanoTime() >= stopAt)) {
							recording = false;
							completion.complete(new AudioClip(buffer.finish(), sampleRate));
						}
					}
					if (count == 0) Thread.sleep(5);
				}
			} catch (Exception e) {
				if (gen == generation && running) LOGGER.error("Microphone capture failed", e);
				synchronized (lock) {
					if (gen == generation && completion != null && !completion.isDone()) completion.completeExceptionally(e);
				}
			} finally {
				if (opened != null) opened.close();
				synchronized (lock) {
					if (gen == generation) { ready = false; running = false; recording = false; line = null; }
				}
			}
		}
		private static TargetDataLine openLine() throws LineUnavailableException {
			for (float rate : new float[] {16000f, 48000f, 44100f}) {
				AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
				DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
				if (!AudioSystem.isLineSupported(info)) continue;
				TargetDataLine candidate = null;
				try {
					candidate = (TargetDataLine) AudioSystem.getLine(info);
					candidate.open(format);
					return candidate;
				} catch (LineUnavailableException e) {
					if (candidate != null) candidate.close();
				}
			}
			throw new LineUnavailableException("No supported microphone input");
		}
	}

	/** Converts captured audio into chat text. */
	public static final class SpeechToText {
		private static final float TARGET_RATE = 16000f;
		private static final String MODEL_NAME = "vosk-model-en-us-0.22-lgraph";
		private static final URI MODEL_URL = URI.create("https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip");

		private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "voiceinject-stt");
			thread.setDaemon(true);
			return thread;
		});

		private Model model;

		public CompletableFuture<List<String>> transcribe(byte[] pcm, float sampleRate) {
			return CompletableFuture.supplyAsync(() -> transcribeBlocking(pcm, sampleRate), executor);
		}

		/** Downloads and loads the speech model in the background so the first recording isn't blocked by the 128 MB download. */
		public void preload() {
			executor.submit(() -> {
				try {
					ensureModel();
					LOGGER.info("Vosk model loaded and ready");
				} catch (Exception e) {
					LOGGER.error("Vosk model preload failed; will retry on first recording", e);
				}
			});
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
			} catch (Exception t) {
				throw new java.util.concurrent.CompletionException(t);
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
			// Remove the superseded download only after the replacement loads successfully.
			try { deleteRecursively(modelDir.getParent().resolve("vosk-model-small-en-us-0.15")); }
			catch (IOException e) { LOGGER.warn("Could not remove the old speech model", e); }
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
					&& Files.isRegularFile(modelDir.resolve("am").resolve("final.mdl"));
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
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
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
		public boolean send(Minecraft client, String text) {
			if (client.player == null) {
				return false;
			}

			String message = text == null ? "" : text.trim();
			if (message.isEmpty()) {
				return false;
			}

			if (message.length() > 256) {
				client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.hot.too_long"), false);
				return false;
			}
			if (message.startsWith("/")) {
				client.player.connection.sendCommand(message.substring(1));
			} else {
				client.player.connection.sendChat(message);
			}
			return true;
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
		private int hotCommand = -1;
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
				hotCommand = -1;
				holdWasDown = false;
				microphone.close();
				if (client.player != null) microphone.warmUp();
				drainClicks();
				return;
			}
			while (Keybinds.openConfig.consumeClick()) {
				if (client.gui.screen() == null) {
					session++;
					sending = false;
					pending = List.of();
					hotCommand = -1;
					microphone.discard();
					holdWasDown = false;
					client.gui.hud.setOverlayMessage(Component.empty(), false);
					client.gui.setScreen(new ConfigScreen(null, config));
					drainClicks();
					return;
				}
			}
			if (sending) { drainClicks(); return; }
			if (microphone.hasCompletedRecording()) { finishSession(client); return; }
			while (Keybinds.cycleHotCommand.consumeClick()) {
				if (client.gui.screen() == null) {
					hotCommand = HotCommands.next(hotCommand, config.hotCommands);
					client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.hot.selected",
							hotCommand < 0 ? Component.translatable("voiceinject.hot.normal") : Component.literal(selectedCommand())), false);
				}
			}
			while (Keybinds.sendHotCommand.consumeClick()) {
				if (client.gui.screen() == null && !microphone.isRecording() && pending.isEmpty() && hotCommand >= 0) {
					if (selectedCommand().contains("{text}")) {
						client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.hot.record"), false);
					} else {
						if (chat.send(client, selectedCommand())) client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.hot.sent"), false);
					}
					while (Keybinds.sendHotCommand.consumeClick()) {}
					break;
				}
			}

			while (Keybinds.cycleAlternative.consumeClick()) {
				if (!pending.isEmpty() && client.gui.screen() == null) {
					selected = (selected + 1) % (pending.size() + 1);
				}
			}
			if (!pending.isEmpty()) showPreview(client);

			if (config.mode == RecordingMode.HOLD) {
				tickHold(client);
			} else {
				tickTap(client);
			}
		}

		private String selectedCommand() {
			return hotCommand >= 0 && hotCommand < config.hotCommands.size() ? config.hotCommands.get(hotCommand) : "";
		}

		private void startRecording(Minecraft client) {
			if (!microphone.start()) {
				microphone.warmUp();
				client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.mic_not_ready"), false);
			}
		}

		private void tickHold(Minecraft client) {
			boolean down = Keybinds.record.isDown() && client.gui.screen() == null;
			if (down && !holdWasDown) {
				startRecording(client);
			} else if (!down && holdWasDown && microphone.isRecording()) {
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
					if (selected < pending.size() && !chat.send(client, HotCommands.forSpeech(selectedCommand(), pending.get(selected)))) {
						drainClicks();
						return;
					}
					pending = List.of();
					client.gui.hud.setOverlayMessage(Component.empty(), false);
					drainClicks();
					return;
				} else if (microphone.isRecording()) {
					finishSession(client);
					drainClicks();
					return;
				} else {
					startRecording(client);
				}
			}
		}

		private void showPreview(Minecraft client) {
			if (selected < pending.size() && HotCommands.forSpeech(selectedCommand(), pending.get(selected)).length() > 256) {
				client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.hot.preview_too_long"), false);
				return;
			}
			client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.preview",
					selected + 1, pending.size() + 1,
					selected == pending.size() ? Component.translatable("voiceinject.discard") : Component.literal(HotCommands.forSpeech(selectedCommand(), pending.get(selected))),
					Keybinds.cycleAlternative.getTranslatedKeyMessage(),
					Keybinds.record.getTranslatedKeyMessage()), false);
		}

		private void finishSession(Minecraft client) {
			CompletableFuture<MicrophoneCapture.AudioClip> captured = microphone.stop();
			sending = true;
			int requestSession = session;
			boolean preview = config.mode == RecordingMode.TAP;
			String command = selectedCommand();
			client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.transcribing"), false);
			captured.thenCompose(clip -> speechToText.transcribe(clip.pcm(), clip.sampleRate())).whenComplete((text, error) -> client.execute(() -> {
				if (requestSession != session || client.player == null || client.player.connection != connection) return;
				drainClicks();
				sending = false;
				microphone.discard();
				if (error != null) {
					LOGGER.error("Speech-to-text failed", error);
					client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.failed"), false);
					return;
				}
				if (text.isEmpty()) {
					client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.no_speech"), false);
				} else if (preview) {
					pending = text;
					selected = 0;
					showPreview(client);
				} else {
					chat.send(client, HotCommands.forSpeech(command, text.get(0)));
					client.gui.hud.setOverlayMessage(Component.empty(), false);
				}
			}));
		}

		private static void drainClicks() {
			while (Keybinds.cycleHotCommand.consumeClick()) {}
			while (Keybinds.sendHotCommand.consumeClick()) {}
			while (Keybinds.cycleAlternative.consumeClick()) {
			}
			while (Keybinds.record.consumeClick()) {
				// drop buffered presses while a send is in flight or the player is missing
			}
			while (Keybinds.openConfig.consumeClick()) {
			}
		}
	}
}
