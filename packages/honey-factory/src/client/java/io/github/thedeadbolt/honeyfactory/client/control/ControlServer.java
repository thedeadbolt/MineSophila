package io.github.thedeadbolt.honeyfactory.client.control;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.control.ControlProtocol;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentSettings;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import io.github.thedeadbolt.honeyfactory.world.WorldGuard;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;

/**
 * Serves the control loop to one external controller at a time on 127.0.0.1, in the
 * protocol of {@code docs/control-protocol.md}. Starting it writes the port and a fresh
 * session token to {@value #CONNECTION_FILE} in the game directory, readable only by its
 * owner; stopping it deletes the file. A student server never sends the true state,
 * masks or commands; a privileged one does.
 *
 * <p>An accept thread takes connections; each connection is served on its own thread,
 * one request at a time, and every environment call runs on the client thread. A
 * connection that ends, however it ends, ends its control session.
 */
public final class ControlServer {
	public static final String CONNECTION_FILE = "honey-factory-control.json";
	static final int MAX_MESSAGE_BYTES = 64 << 20;
	private static final String DEFAULT_PRESET = "level1";
	private static @Nullable ControlServer running;

	private final ServerSocket socket;
	private final boolean privileged;
	private final byte[] token;
	private final Path file;
	private final AtomicReference<@Nullable Socket> active = new AtomicReference<>();
	private volatile boolean closed;

	private ControlServer(ServerSocket socket, boolean privileged, byte[] token, Path file) {
		this.socket = socket;
		this.privileged = privileged;
		this.token = token;
		this.file = file;
	}

	/**
	 * Starts serving, replacing any running server. Call on the client thread; the world's
	 * claim is checked on the server thread without blocking either.
	 *
	 * @param port the port, or 0 for any free one
	 * @return the started server, or a failure with {@code NOT_OWNED} outside a claimed dataset world
	 */
	public static CompletableFuture<ControlServer> start(int port, boolean privileged) {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.NO_LOCAL_RUNTIME, "the control server needs a local world"));
		}

		return server.submit(() -> WorldGuard.requireOwned(server)).thenApplyAsync(owned -> bind(minecraft, port, privileged), minecraft);
	}

	private static synchronized ControlServer bind(Minecraft minecraft, int port, boolean privileged) {
		stop();
		try {
			ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
			byte[] token = new byte[32];
			new SecureRandom().nextBytes(token);
			Path file = minecraft.gameDirectory.toPath().resolve(CONNECTION_FILE);
			ControlServer started = new ControlServer(socket, privileged, HexFormat.of().formatHex(token).getBytes(StandardCharsets.US_ASCII), file);
			started.writeConnectionFile();
			Thread acceptor = new Thread(started::accept, "honey-factory-control");
			acceptor.setDaemon(true);
			acceptor.start();
			running = started;
			HoneyFactory.LOGGER.info("Control server listening on 127.0.0.1:{} ({})", socket.getLocalPort(), privileged ? "privileged" : "student");
			return started;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Stops the running server, if any, closing its connection and deleting the connection file. */
	public static synchronized void stop() {
		ControlServer server = running;
		running = null;
		if (server != null) {
			server.close();
		}
	}

	public static @Nullable ControlServer running() {
		return running;
	}

	public int port() {
		return this.socket.getLocalPort();
	}

	public boolean privileged() {
		return this.privileged;
	}

	public boolean connected() {
		return this.active.get() != null;
	}

	private void writeConnectionFile() throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("port", this.socket.getLocalPort());
		json.addProperty("token", new String(this.token, StandardCharsets.US_ASCII));
		json.addProperty("privileged", this.privileged);
		Path temporary = this.file.resolveSibling(CONNECTION_FILE + ".tmp");
		Files.deleteIfExists(temporary);
		try {
			Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} catch (UnsupportedOperationException e) {
			Files.createFile(temporary);
		}

		Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8);
		Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private void close() {
		this.closed = true;
		try {
			this.socket.close();
		} catch (IOException ignored) {
			// Closing anyway.
		}

		Socket connection = this.active.get();
		if (connection != null) {
			try {
				connection.close();
			} catch (IOException ignored) {
				// Its thread ends the session.
			}
		}

		try {
			Files.deleteIfExists(this.file);
		} catch (IOException e) {
			HoneyFactory.LOGGER.warn("Could not delete {}: {}", this.file, e.getMessage());
		}
	}

	private void accept() {
		while (!this.closed) {
			Socket connection;
			try {
				connection = this.socket.accept();
			} catch (IOException e) {
				return;
			}

			if (!this.active.compareAndSet(null, connection)) {
				refuse(connection);
				continue;
			}

			Thread thread = new Thread(() -> serve(connection), "honey-factory-control-connection");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static void refuse(Socket connection) {
		try (connection) {
			new ControlProtocol.Message(ControlProtocol.error("BUSY", "another controller is connected"), List.of()).write(connection.getOutputStream());
		} catch (IOException ignored) {
			// It has gone already.
		}
	}

	/** Serves one connection until it closes, says close, or sends a malformed frame; then ends its session. */
	private void serve(Socket connection) {
		Session session = new Session();
		try (connection) {
			InputStream in = connection.getInputStream();
			OutputStream out = connection.getOutputStream();
			while (!this.closed) {
				ControlProtocol.Message request = ControlProtocol.Message.read(in, MAX_MESSAGE_BYTES);
				Reply reply = handle(session, request);
				reply.message().write(out);
				if (reply.last()) {
					return;
				}
			}
		} catch (IOException e) {
			HoneyFactory.LOGGER.debug("Control connection ended: {}", e.getMessage());
		} finally {
			if (session.controlling) {
				onClient(() -> {
					EnvironmentRuntime.get().controlEnd();
					return CompletableFuture.completedFuture(null);
				});
			}

			this.active.set(null);
		}
	}

	private record Reply(ControlProtocol.Message message, boolean last) {
		static Reply of(JsonObject header) {
			return new Reply(new ControlProtocol.Message(header, List.of()), false);
		}
	}

	private static final class Session {
		private boolean greeted;
		private boolean controlling;
	}

	private Reply handle(Session session, ControlProtocol.Message request) {
		try {
			String type = ControlProtocol.type(request.header());
			if (!session.greeted && !type.equals("hello")) {
				return Reply.of(ControlProtocol.error("BAD_MESSAGE", "send hello first"));
			}

			return switch (type) {
				case "hello" -> hello(session, JsonFields.of(request.header()));
				case "reset" -> {
					session.controlling = true;
					yield observation(reset(JsonFields.of(request.header())));
				}
				case "step" -> observation(step(JsonFields.of(request.header())));
				case "close" -> {
					JsonObject header = new JsonObject();
					header.addProperty("type", "close");
					yield new Reply(new ControlProtocol.Message(header, List.of()), true);
				}
				default -> Reply.of(ControlProtocol.error("BAD_MESSAGE", "a client does not send " + type));
			};
		} catch (EnvironmentException e) {
			return Reply.of(ControlProtocol.error(e.reason().name(), e.getMessage()));
		} catch (ContractException | IllegalArgumentException e) {
			return Reply.of(ControlProtocol.error("BAD_MESSAGE", e.getMessage()));
		} catch (RuntimeException e) {
			HoneyFactory.LOGGER.warn("Control request failed", e);
			return Reply.of(ControlProtocol.error("INTERNAL", String.valueOf(e)));
		}
	}

	private Reply hello(Session session, JsonFields request) {
		byte[] offered = request.string("token").getBytes(StandardCharsets.US_ASCII);
		if (request.integer32("version", Integer.MIN_VALUE, Integer.MAX_VALUE) != ControlProtocol.PROTOCOL_VERSION || !MessageDigest.isEqual(offered, this.token)) {
			return Reply.of(ControlProtocol.error("UNAUTHORISED", "wrong token or protocol version"));
		}

		session.greeted = true;
		EnvironmentSettings settings = onClient(() -> CompletableFuture.completedFuture(EnvironmentRuntime.get().settings()));
		MotorLimits motor = settings.motor();
		JsonObject limits = new JsonObject();
		limits.addProperty("max_turn_rate", motor.maxTurnRate());
		limits.addProperty("max_turn_accel", motor.maxTurnAccel());
		limits.addProperty("max_pitch_rate", motor.maxPitchRate());
		limits.addProperty("max_pitch_accel", motor.maxPitchAccel());
		JsonObject reply = new JsonObject();
		reply.addProperty("type", "hello");
		reply.addProperty("version", ControlProtocol.PROTOCOL_VERSION);
		reply.addProperty("privileged", this.privileged);
		JsonObject described = new JsonObject();
		described.add("motor", limits);
		described.addProperty("auto_jump", settings.autoJump());
		described.addProperty("eye_height_blocks", ConfigResolver.EYE_HEIGHT_BLOCKS);
		reply.add("settings", described);
		return Reply.of(reply);
	}

	/** Builds a platform recipe's scene as a dataset with this seed would, and starts controlling it. */
	private EnvironmentRuntime.ControlResult reset(JsonFields request) {
		SceneSpec spec = SceneSpec.read(request.objectOrEmpty("scene"), DEFAULT_PRESET);
		if (!(spec.base() instanceof SceneBase.Platform)) {
			throw new EnvironmentException(EnvironmentException.Reason.UNSUPPORTED, "control scenes must use a platform base");
		}

		long seed = request.integer("seed", 0, SceneSeeds.MAX_SEED);
		int index = request.integer32("scene_index", 0, Ids.MAX_SCENES - 1, 0);
		JsonFields capture = request.object("capture");
		CaptureMode mode = capture.enumValue("mode", CaptureMode::fromJson);
		CaptureProjection projection = CaptureProjection.of(mode, capture.integer32("width", 16, 4096), capture.integer32("height", 16, 4096),
			capture.has("cube_face_size") ? capture.integer32("cube_face_size", 16, 4096) : null);
		ScenePreview preview = ScenePreview.of(spec, seed, index);
		CameraPose start;
		if (request.has("start")) {
			JsonFields pose = request.object("start");
			start = new ViewpointPlan(pose.decimal("x", -512, 512), pose.decimal("y", -512, 512), pose.decimal("z", -512, 512),
				pose.decimal("yaw", -360, 360), pose.decimal("pitch", -90, 90)).pose(preview.arena());
		} else if (preview.viewpoint() != null) {
			start = preview.viewpoint().pose(preview.arena());
		} else {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, "the scene has no placeable first viewpoint; give a start");
		}

		CameraPose first = start;
		return onClient(() -> EnvironmentRuntime.get().controlReset(preview.arena(), preview.scene().plan(), first, projection, seed));
	}

	private EnvironmentRuntime.ControlResult step(JsonFields request) {
		MotorCommand student = command(request, "student");
		MotorCommand teacher = command(request, "teacher");
		if (teacher != null && !this.privileged) {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, "only a privileged connection sends teacher commands");
		}

		String executed = request.string("executed", "student");
		ControlStep.Executed side = switch (executed) {
			case "student" -> ControlStep.Executed.STUDENT;
			case "teacher" -> ControlStep.Executed.TEACHER;
			default -> throw request.error("executed", "must be student or teacher");
		};
		return onClient(() -> EnvironmentRuntime.get().controlStep(student, teacher, side));
	}

	private static @Nullable MotorCommand command(JsonFields request, String name) {
		return request.has(name) && !request.raw().get(name).isJsonNull() ? ControlProtocol.command(request.object(name).raw()) : null;
	}

	private Reply observation(EnvironmentRuntime.ControlResult result) {
		ControlStep step = result.step();
		CapturedImages images = result.capture().images();
		JsonObject header = new JsonObject();
		header.addProperty("type", "observation");
		header.addProperty("index", step.index());
		header.addProperty("width", images.width());
		header.addProperty("height", images.height());
		header.add("senses", step.sensesJson());
		if (!this.privileged) {
			return new Reply(new ControlProtocol.Message(header, List.of(images.rgb())), false);
		}

		header.add("commands", step.commandsJson());
		header.add("truth", step.truthJson());
		return new Reply(new ControlProtocol.Message(header, List.of(images.rgb(), images.visibleMask(), images.unoccludedMask())), false);
	}

	/** Runs an environment call on the client thread and waits for its result. */
	private static <T> T onClient(Supplier<CompletableFuture<T>> call) {
		try {
			return Minecraft.getInstance().submit(call::get).join().join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			while (cause instanceof CompletionException nested && nested.getCause() != null) {
				cause = nested.getCause();
			}

			throw cause instanceof RuntimeException runtime ? runtime : e;
		}
	}
}
