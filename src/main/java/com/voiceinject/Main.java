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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
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

		controller = new VoiceController(
				config,
				new MicrophoneCapture(config),
				new SpeechToText(config),
				new ChatInjector()
		);

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
		public SpeechModel speechModel = SpeechModel.SMALL;
		public List<String> hotCommands = List.of();
		public String microphoneSource = "@DEFAULT_SOURCE@";
		private static Path path() {
			return FabricLoader.getInstance().getConfigDir().resolve("voiceinject.json");
		}
		public static Config load() {
			Config result = new Config();
			if (Files.exists(path())) {
				try {
					JsonObject saved = JsonParser.parseString(Files.readString(path())).getAsJsonObject();
					if (saved.has("mode")) result.mode = RecordingMode.valueOf(saved.get("mode").getAsString());
					if (saved.has("speechModel")) result.speechModel = SpeechModel.valueOf(saved.get("speechModel").getAsString());
					if (saved.has("microphoneSource") && saved.get("microphoneSource").isJsonPrimitive()) {
						result.microphoneSource = saved.get("microphoneSource").getAsString();
					}
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
					saved.addProperty("speechModel", speechModel.name());
					saved.addProperty("microphoneSource", microphoneSource);
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
		private Component microphoneLabel() {
			String source = settings.microphoneSource.equals("@DEFAULT_SOURCE@")
					? Component.translatable("voiceinject.microphone.default").getString()
					: settings.microphoneSource;
			if (source.length() > 44) source = source.substring(0, 41) + "...";
			return Component.translatable("voiceinject.microphone", source);
		}
		private Component modelLabel() {
			return Component.translatable("voiceinject.model",
					Component.translatable("voiceinject.model." + settings.speechModel.name().toLowerCase(java.util.Locale.ROOT)),
					settings.speechModel.downloadSize());
		}
		@Override protected void init() {
			List<String> sources = MicrophoneCapture.availableSources();
			addRenderableWidget(Button.builder(modeLabel(), button -> {
				settings.mode = settings.mode == RecordingMode.HOLD ? RecordingMode.TAP : RecordingMode.HOLD;
				saveFailed = !settings.save();
				button.setMessage(modeLabel());
			}).bounds(width / 2 - 100, height / 2 - 56, 200, 20).build());
			addRenderableWidget(Button.builder(modelLabel(), button -> {
				settings.speechModel = settings.speechModel.next();
				controller.speechToText.prepare(settings.speechModel);
				saveFailed = !settings.save();
				button.setMessage(modelLabel());
			}).bounds(width / 2 - 100, height / 2 - 32, 200, 20).build());
			addRenderableWidget(Button.builder(microphoneLabel(), button -> {
				int current = sources.indexOf(settings.microphoneSource);
				settings.microphoneSource = sources.get((current + 1) % sources.size());
				saveFailed = !settings.save();
				button.setMessage(microphoneLabel());
			}).bounds(width / 2 - 100, height / 2 - 8, 200, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("voiceinject.keybinds"), button ->
					minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)))
					.bounds(width / 2 - 100, height / 2 + 16, 98, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("voiceinject.hot.title"), button ->
					minecraft.gui.setScreen(new HotCommandScreen(this, settings)))
					.bounds(width / 2 + 2, height / 2 + 16, 98, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
					.bounds(width / 2 - 100, height / 2 + 58, 200, 20).build());
		}
		@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.centeredText(font, title, width / 2, height / 2 - 82, 0xFFFFFFFF);
			graphics.centeredText(font, Component.translatable("voiceinject.settings.help"), width / 2, height / 2 + 42, 0xFFCCCCCC);
			if (saveFailed) graphics.centeredText(font, Component.translatable("voiceinject.settings.save_failed"), width / 2, height / 2 + 82, 0xFFFF5555);
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

	/** Captures PCM from a fresh microphone stream for each recording. */
	public static final class MicrophoneCapture {
		private static final int MAX_SECONDS = 30;
		private static final int READ_CHUNK = 4096;
		private static final long JOIN_TIMEOUT_MS = 500L;

		private final Config config;
		private final AtomicBoolean recording = new AtomicBoolean();
		private final AtomicInteger session = new AtomicInteger();
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private volatile TargetDataLine line;
		private volatile Process pulseProcess;
		private volatile InputStream pulseStream;
		private volatile Thread worker;
		private volatile float sampleRate = 16000f;

		public record AudioClip(byte[] pcm, float sampleRate) {}
		public MicrophoneCapture(Config config) { this.config = config; }
		public boolean isRecording() { return recording.get(); }
		public void warmUp() { /* The stream is opened only while recording. */ }
		public static List<String> availableSources() {
			List<String> sources = new ArrayList<>();
			sources.add("@DEFAULT_SOURCE@");
			if (!isLinux()) return sources;
			try {
				Process query = new ProcessBuilder("pactl", "list", "short", "sources").start();
				try (var reader = query.inputReader()) {
					reader.lines().map(line -> line.split("\\t"))
							.filter(columns -> columns.length > 1 && !columns[1].endsWith(".monitor"))
							.map(columns -> columns[1]).filter(source -> !sources.contains(source))
							.forEach(sources::add);
				}
				query.waitFor();
			} catch (IOException e) {
				LOGGER.debug("PulseAudio source listing unavailable", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return List.copyOf(sources);
		}
		public boolean start() {
			if (!recording.compareAndSet(false, true)) return false;
			synchronized (buffer) {
				buffer.reset();
			}
			TargetDataLine opened = null;
			InputStream input = null;
			Process process = null;
			try {
				if (isLinux()) {
					try {
						String source = usablePulseSource();
						process = new ProcessBuilder("parec",
								"--device=" + source,
								"--client-name=voiceinject", "--stream-name=Minecraft voice recognition",
								"--rate=16000", "--format=s16le", "--channels=1", "--raw")
								.redirectError(ProcessBuilder.Redirect.DISCARD).start();
						input = process.getInputStream();
						if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
							throw new IOException("parec exited immediately with status " + process.exitValue());
						}
						sampleRate = 16000f;
						LOGGER.info("Recording with PulseAudio/PipeWire source {}", source);
					} catch (IOException e) {
						LOGGER.warn("parec is unavailable; falling back to Java Sound: {}", e.toString());
						if (input != null) try { input.close(); } catch (IOException ignored) {}
						if (process != null) process.destroyForcibly();
						input = null;
						process = null;
						opened = openLine();
						opened.start();
						sampleRate = opened.getFormat().getSampleRate();
					}
				} else {
					opened = openLine();
					opened.start();
					sampleRate = opened.getFormat().getSampleRate();
				}
			} catch (LineUnavailableException | SecurityException e) {
				recording.set(false);
				if (process != null) process.destroyForcibly();
				LOGGER.error("Could not start microphone recording", e);
				return false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				recording.set(false);
				if (process != null) process.destroyForcibly();
				return false;
			}
			int id;
			synchronized (session) {
				id = session.incrementAndGet();
				line = opened;
				pulseProcess = process;
				pulseStream = input;
			}
			TargetDataLine captureLine = opened;
			InputStream captureStream = input;
			Process captureProcess = process;
			Thread capture = new Thread(() -> captureLoop(id, captureLine, captureStream, captureProcess), "voiceinject-mic");
			capture.setDaemon(true);
			worker = capture;
			capture.start();
			LOGGER.debug("Microphone recording started");
			return true;
		}
		public CompletableFuture<AudioClip> stop() {
			if (!recording.compareAndSet(true, false)) {
				return CompletableFuture.completedFuture(new AudioClip(new byte[0], sampleRate));
			}
			TargetDataLine currentLine = line;
			InputStream currentStream = pulseStream;
			Process currentProcess = pulseProcess;
			if (currentLine != null) closeQuietly(currentLine);
			if (currentProcess != null) currentProcess.destroy();
			if (currentStream != null) try { currentStream.close(); } catch (IOException ignored) {}
			Thread currentWorker = worker;
			if (currentWorker != null && currentWorker != Thread.currentThread()) {
				try { currentWorker.join(JOIN_TIMEOUT_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			}
			synchronized (session) {
				session.incrementAndGet();
				if (line == currentLine) line = null;
				if (pulseStream == currentStream) pulseStream = null;
				if (pulseProcess == currentProcess) pulseProcess = null;
			}
			worker = null;
			byte[] pcm;
			synchronized (buffer) { pcm = buffer.toByteArray(); }
			LOGGER.info("Microphone recording stopped ({} bytes at {} Hz, peak {})",
					pcm.length, (int) sampleRate, peakAmplitude(pcm));
			return CompletableFuture.completedFuture(new AudioClip(pcm, sampleRate));
		}
		public boolean hasCompletedRecording() { return false; }
		public void discard() {
			if (recording.get()) stop();
			synchronized (buffer) { buffer.reset(); }
		}
		public void close() { discard(); }

		private void captureLoop(int id, TargetDataLine opened, InputStream input, Process process) {
			try {
				int maximum = (int) sampleRate * 2 * MAX_SECONDS;
				byte[] chunk = new byte[READ_CHUNK];
				while (recording.get() && session.get() == id) {
					int count = input != null ? input.read(chunk) : opened.read(chunk, 0, chunk.length);
					if (count <= 0) break;
					synchronized (buffer) {
						if (session.get() != id || buffer.size() >= maximum) break;
						buffer.write(chunk, 0, Math.min(count, maximum - buffer.size()));
					}
				}
			} catch (IOException | RuntimeException e) {
				if (recording.get()) LOGGER.error("Microphone capture failed", e);
			} finally {
				if (opened != null) closeQuietly(opened);
				if (input != null) try { input.close(); } catch (IOException ignored) {}
				if (process != null) process.destroyForcibly();
			}
		}

		private String usablePulseSource() {
			String requested = config.microphoneSource;
			if (requested == null || requested.isBlank() || requested.equals("@DEFAULT_SOURCE@")) {
				return "@DEFAULT_SOURCE@";
			}
			if (availableSources().contains(requested)) return requested;
			LOGGER.warn("Saved microphone source '{}' is unavailable on this computer; using the system default", requested);
			return "@DEFAULT_SOURCE@";
		}

		private static int peakAmplitude(byte[] pcm) {
			int peak = 0;
			for (int i = 0; i + 1 < pcm.length; i += 2) {
				int sample = Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)));
				if (sample > peak) peak = sample;
			}
			return peak;
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

		private static void closeQuietly(TargetDataLine current) {
			try { current.stop(); } catch (RuntimeException ignored) {}
			try { current.flush(); } catch (RuntimeException ignored) {}
			try { current.close(); } catch (RuntimeException ignored) {}
		}

		private static boolean isLinux() {
			return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
		}
	}

	/** Converts captured audio into chat text. */
	public static final class SpeechToText {
		private static final float TARGET_RATE = 16000f;

		private final Config config;
		private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "voiceinject-stt");
			thread.setDaemon(true);
			return thread;
		});
		private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "voiceinject-model-download");
			thread.setDaemon(true);
			return thread;
		});
		private final Set<SpeechModel> downloading = ConcurrentHashMap.newKeySet();

		private volatile Model model;
		private volatile SpeechModel loadedModel;

		public SpeechToText(Config config) {
			this.config = config;
			prepare(config.speechModel);
		}

		static final class ModelUnavailableException extends Exception {
			final SpeechModel speechModel;
			ModelUnavailableException(SpeechModel speechModel) {
				super("Recognition model is still downloading: " + speechModel);
				this.speechModel = speechModel;
			}
		}

		void prepare(SpeechModel selected) {
			if (modelReady(selected) || !downloading.add(selected)) {
				return;
			}
			downloadExecutor.execute(() -> {
				try {
					ensureModelOnDisk(selected);
					LOGGER.info("Downloaded {} Vosk model", selected);
				} catch (Exception e) {
					LOGGER.error("Could not download {} Vosk model", selected, e);
				} finally {
					downloading.remove(selected);
				}
			});
		}

		private boolean modelReady(SpeechModel selected) {
			return isModelReady(FabricLoader.getInstance().getConfigDir().resolve("voiceinject").resolve(selected.directory()));
		}

		public CompletableFuture<List<String>> transcribe(byte[] pcm, float sampleRate) {
			SpeechModel selected = config.speechModel;
			if (!modelReady(selected)) {
				prepare(selected);
				return CompletableFuture.failedFuture(new ModelUnavailableException(selected));
			}
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
				Model loaded = ensureModel(config.speechModel);
				try (Recognizer recognizer = new Recognizer(loaded, TARGET_RATE)) {
					recognizer.setMaxAlternatives(10);
					recognizer.acceptWaveForm(sixteenKhz, sixteenKhz.length);
					List<String> text = extractAlternatives(recognizer.getFinalResult());
					if (text.isEmpty()) {
						LOGGER.info("No speech detected");
					} else {
						LOGGER.info("Speech detected: {}", text);
					}
					return text;
				}
			} catch (Exception t) {
				throw new java.util.concurrent.CompletionException(t);
			}
		}

		private Model ensureModel(SpeechModel selected) throws IOException, ModelUnavailableException {
			if (model != null && loadedModel == selected) {
				return model;
			}
			if (!modelReady(selected)) {
				prepare(selected);
				throw new ModelUnavailableException(selected);
			}
			LibVosk.setLogLevel(LogLevel.WARNINGS);
			Path modelDir = FabricLoader.getInstance().getConfigDir().resolve("voiceinject").resolve(selected.directory());
			LOGGER.info("Loading {} Vosk model from {}", selected, modelDir);
			Model replacement = new Model(modelDir.toString());
			Model previous = model;
			model = null;
			loadedModel = null;
			if (previous != null) {
				try {
					previous.close();
				} catch (RuntimeException e) {
					LOGGER.warn("Could not close previous Vosk model", e);
				}
			}
			LOGGER.info("Loading {} Vosk model from {}", selected, modelDir);
			model = new Model(modelDir.toString());
			loadedModel = selected;
			LOGGER.info("Vosk model loaded: {}", selected);
			return model;
		}

		private static Path ensureModelOnDisk(SpeechModel selected) throws IOException, InterruptedException {
			Path modelDir = FabricLoader.getInstance().getConfigDir().resolve("voiceinject").resolve(selected.directory());
			if (isModelReady(modelDir)) {
				return modelDir;
			}
			if (Files.exists(modelDir)) {
				deleteRecursively(modelDir);
			}
			Path parent = modelDir.getParent();
			Files.createDirectories(parent);
			LOGGER.info("Downloading {} Vosk model ({}) from {}", selected, selected.downloadSize(), selected.downloadUri());
			Path zip = Files.createTempFile("voiceinject-vosk-", ".zip");
			try {
				download(selected.downloadUri(), zip);
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
					.timeout(Duration.ofMinutes(30))
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
		private List<String> pending = List.of();
		private int selected;
		private int announcedSelection = -1;
		private int hotCommand = -1;
		private int session;
		private int transcription;
		private Object connection;
		private boolean showConfigOnBoot = true;

		public VoiceController(Config config, MicrophoneCapture microphone, SpeechToText speechToText, ChatInjector chat) {
			this.config = config;
			this.microphone = microphone;
			this.speechToText = speechToText;
			this.chat = chat;
		}

		public void tick(Minecraft client) {
			if (showConfigOnBoot && client.gui.overlay() == null && client.gui.screen() != null) {
				showConfigOnBoot = false;
				Screen parent = client.gui.screen();
				client.gui.setScreen(new ConfigScreen(parent, config));
				drainClicks();
				return;
			}
			if (client.player == null || connection != client.player.connection) {
				connection = client.player == null ? null : client.player.connection;
				session++;
				transcription++;
				pending = List.of();
				announcedSelection = -1;
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
					transcription++;
					pending = List.of();
					announcedSelection = -1;
					hotCommand = -1;
					microphone.discard();
					holdWasDown = false;
					client.gui.hud.setOverlayMessage(Component.empty(), false);
					client.gui.setScreen(new ConfigScreen(null, config));
					drainClicks();
					return;
				}
			}
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
			if (microphone.start()) {
				// A new recording supersedes any transcription still loading or running.
				transcription++;
				client.gui.hud.setOverlayMessage(Component.empty(), false);
			} else {
				microphone.warmUp();
				showStatus(client, Component.translatable("voiceinject.mic_not_ready"));
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
					announcedSelection = -1;
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
			Component option = selected == pending.size()
					? Component.translatable("voiceinject.discard")
					: Component.literal(HotCommands.forSpeech(selectedCommand(), pending.get(selected)));
			Component preview = Component.translatable("voiceinject.preview",
					selected + 1, pending.size() + 1,
					option,
					Keybinds.cycleAlternative.getTranslatedKeyMessage(),
					Keybinds.record.getTranslatedKeyMessage());
			client.gui.hud.setOverlayMessage(preview, false);
			if (announcedSelection != selected) {
				announcedSelection = selected;
				client.player.sendSystemMessage(preview);
			}
		}

		private void finishSession(Minecraft client) {
			CompletableFuture<MicrophoneCapture.AudioClip> captured = microphone.stop();
			int requestSession = session;
			int requestTranscription = ++transcription;
			boolean preview = config.mode == RecordingMode.TAP;
			String command = selectedCommand();
			client.gui.hud.setOverlayMessage(Component.translatable("voiceinject.transcribing"), false);
			captured.thenCompose(clip -> speechToText.transcribe(clip.pcm(), clip.sampleRate())).whenComplete((text, error) -> client.execute(() -> {
				if (requestSession != session || requestTranscription != transcription || client.player == null) return;
				drainClicks();
				microphone.discard();
				if (error != null) {
					Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
							? error.getCause() : error;
					if (cause instanceof SpeechToText.ModelUnavailableException unavailable) {
						showStatus(client, Component.translatable("voiceinject.model_downloading",
								Component.translatable("voiceinject.model." + unavailable.speechModel.name().toLowerCase(java.util.Locale.ROOT)),
								unavailable.speechModel.downloadSize()));
						return;
					}
					LOGGER.error("Speech-to-text failed", error);
					showStatus(client, Component.translatable("voiceinject.failed"));
					return;
				}
				if (text.isEmpty()) {
					showStatus(client, Component.translatable("voiceinject.no_speech"));
				} else if (preview) {
					pending = text;
					selected = 0;
					announcedSelection = -1;
					showPreview(client);
				} else {
					chat.send(client, HotCommands.forSpeech(command, text.get(0)));
					client.gui.hud.setOverlayMessage(Component.empty(), false);
				}
			}));
		}

		private static void showStatus(Minecraft client, Component message) {
			client.gui.hud.setOverlayMessage(message, false);
			if (client.player != null) client.player.sendSystemMessage(message);
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
