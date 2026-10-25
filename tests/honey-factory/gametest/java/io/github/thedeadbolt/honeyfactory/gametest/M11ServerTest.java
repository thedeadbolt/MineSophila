package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.client.control.ControlServer;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.client.runtime.TickStepper;
import io.github.thedeadbolt.honeyfactory.control.ControlProtocol;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.KeyboardInput;

/**
 * M11 task 7: the loopback control server. Tokens, one controller at a time, the student
 * and privileged views, a client vanishing mid-step, malformed frames, and refusing worlds
 * that are not claimed. Socket I/O runs on a helper thread, because the game only runs
 * while the test thread waits.
 */
public class M11ServerTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m11-server";
	private static final int TIMEOUT_TICKS = 1200;
	private static final boolean USER_AUTO_JUMP = false;
	private static final String RESET = """
		{"type": "reset", "scene": {"preset": "level1"}, "seed": 3, "capture": {"mode": "PERSPECTIVE_90", "width": 64, "height": 48}}
		""";
	private static final String FORWARD = """
		{"type": "step", "student": {"forward": 1.0, "turn_rate": 2.0}, "executed": "student"}
		""";

	private final ExecutorService io = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "m11-server-test");
		thread.setDaemon(true);
		return thread;
	});

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		GameTestSupport.pinCaptureSettings(context);
		context.runOnClient(minecraft -> minecraft.options.autoJump().set(USER_AUTO_JUMP));
		List<String> failures = new ArrayList<>();
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			CompletableFuture<ControlServer> refused = context.computeOnClient(minecraft -> ControlServer.start(0, false));
			context.waitFor(minecraft -> refused.isDone(), TIMEOUT_TICKS);
			EnvironmentException.Reason unclaimed = refused.handle((server, error) -> error != null && error.getCause() instanceof EnvironmentException e ? e.reason() : null).join();
			if (unclaimed != EnvironmentException.Reason.NOT_OWNED) {
				failures.add("starting in an unclaimed world gave " + unclaimed);
				context.runOnClient(minecraft -> ControlServer.stop());
			}

			GameTestSupport.claim(world, WORLD_NAME);
			student(context, failures);
			privileged(context, failures);
		}

		this.io.shutdownNow();
		if (!failures.isEmpty()) {
			throw new AssertionError("M11 server failures:\n" + String.join("\n", failures));
		}
	}

	private void student(ClientGameTestContext context, List<String> failures) {
		Path file = start(context, false);
		JsonObject connection = read(file);
		try (Client first = connect(context, connection)) {
			expectError(failures, "wrong token", "UNAUTHORISED", first.request(context, hello("not-the-token")));
			ControlProtocol.Message hello = first.request(context, hello(connection.get("token").getAsString()));
			if (!"hello".equals(hello.header().get("type").getAsString()) || hello.header().get("privileged").getAsBoolean()) {
				failures.add("student hello reply: " + hello.header());
			}

			try (Client second = connect(context, connection)) {
				expectError(failures, "second connection", "BUSY", second.receive(context));
			}

			ControlProtocol.Message reset = first.request(context, message(RESET));
			ControlProtocol.Message step = first.request(context, message(FORWARD));
			for (ControlProtocol.Message observation : List.of(reset, step)) {
				JsonObject header = observation.header();
				if (!"observation".equals(header.get("type").getAsString()) || header.has("truth") || header.has("commands") || observation.blobs().size() != 1
					|| observation.blobs().getFirst().length != 64 * 48 * 3 || !header.getAsJsonObject("senses").has("forward_speed")) {
					failures.add("student observation " + header + " with " + observation.blobs().size() + " blobs");
				}
			}

			if (step.header().get("index").getAsInt() != 1) {
				failures.add("the first step came back as index " + step.header().get("index"));
			}

			expectError(failures, "teacher command from a student", "INVALID_REQUEST",
				first.request(context, message("{\"type\": \"step\", \"student\": {}, \"teacher\": {\"turn_rate\": 1}, \"executed\": \"teacher\"}")));
			// Vanish right after sending a step.
			first.send(message(FORWARD));
		}

		context.waitFor(minecraft -> EnvironmentRuntime.get().lastControlStep() == null && !TickStepper.get().held(), TIMEOUT_TICKS);
		boolean keyboard = context.computeOnClient(minecraft -> minecraft.player.input instanceof KeyboardInput);
		boolean autoJump = context.computeOnClient(minecraft -> minecraft.options.autoJump().get());
		if (!keyboard || autoJump != USER_AUTO_JUMP) {
			failures.add("a vanished client left keyboard input " + keyboard + " and auto-jump " + autoJump);
		}

		try (Client again = connect(context, connection)) {
			again.request(context, hello(connection.get("token").getAsString()));
			if (!"observation".equals(again.request(context, message(RESET)).header().get("type").getAsString())) {
				failures.add("a new connection could not reset after a client vanished");
			}

			// Five bytes of header that are not JSON: only this connection closes.
			again.raw(new byte[] {0, 0, 0, 5, '{', '{', '{', '{', '{'});
			if (again.receiveOrClosed(context) != null) {
				failures.add("a malformed frame did not close its connection");
			}
		}

		try (Client after = connect(context, connection)) {
			if (!"hello".equals(after.request(context, hello(connection.get("token").getAsString())).header().get("type").getAsString())) {
				failures.add("the server stopped accepting after a malformed frame");
			}

			after.request(context, message("{\"type\": \"close\"}"));
		}

		context.runOnClient(minecraft -> ControlServer.stop());
		if (Files.exists(file)) {
			failures.add("stopping left " + file);
		}
	}

	private void privileged(ClientGameTestContext context, List<String> failures) {
		Path file = start(context, true);
		JsonObject connection = read(file);
		try (Client client = connect(context, connection)) {
			if (!client.request(context, hello(connection.get("token").getAsString())).header().get("privileged").getAsBoolean()) {
				failures.add("the privileged hello reply is not privileged");
			}

			client.request(context, message(RESET));
			ControlProtocol.Message step = client.request(context, message(
				"{\"type\": \"step\", \"student\": {\"forward\": 1}, \"teacher\": {\"turn_rate\": 9}, \"executed\": \"teacher\"}"));
			JsonObject header = step.header();
			if (!header.has("truth") || !header.getAsJsonObject("truth").has("target") || step.blobs().size() != 3
				|| !"teacher".equals(header.getAsJsonObject("commands").get("executed").getAsString())) {
				failures.add("privileged observation " + header + " with " + step.blobs().size() + " blobs");
			}

			client.request(context, message("{\"type\": \"close\"}"));
		}

		context.runOnClient(minecraft -> ControlServer.stop());
	}

	/** Starts the server and returns its connection file. */
	private static Path start(ClientGameTestContext context, boolean privileged) {
		CompletableFuture<ControlServer> started = context.computeOnClient(minecraft -> ControlServer.start(0, privileged));
		context.waitFor(minecraft -> started.isDone(), TIMEOUT_TICKS);
		started.join();
		return context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve(ControlServer.CONNECTION_FILE));
	}

	private static JsonObject read(Path file) {
		try {
			return JsonFields.parseObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static ControlProtocol.Message hello(String token) {
		return message("{\"type\": \"hello\", \"version\": 1, \"token\": \"" + token + "\"}");
	}

	private static ControlProtocol.Message message(String json) {
		return new ControlProtocol.Message(JsonFields.parseObject(json), List.of());
	}

	private static void expectError(List<String> failures, String what, String code, ControlProtocol.Message reply) {
		JsonObject header = reply.header();
		if (!"error".equals(header.get("type").getAsString()) || !code.equals(header.get("code").getAsString())) {
			failures.add(what + ": expected error " + code + ", got " + header);
		}
	}

	private Client connect(ClientGameTestContext context, JsonObject connection) {
		return new Client(blocking(context, () -> {
			Socket socket = new Socket(InetAddress.getLoopbackAddress(), connection.get("port").getAsInt());
			socket.setSoTimeout(60_000);
			return socket;
		}));
	}

	/** Runs blocking I/O off the test thread and lets the game run until it finishes. */
	private <T> T blocking(ClientGameTestContext context, IoCall<T> call) {
		CompletableFuture<T> future = CompletableFuture.supplyAsync(asSupplier(call), this.io);
		context.waitFor(minecraft -> future.isDone(), TIMEOUT_TICKS);
		return future.join();
	}

	private static <T> Supplier<T> asSupplier(IoCall<T> call) {
		return () -> {
			try {
				return call.call();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	@FunctionalInterface
	private interface IoCall<T> {
		T call() throws IOException;
	}

	private final class Client implements AutoCloseable {
		private final Socket socket;

		Client(Socket socket) {
			this.socket = socket;
		}

		ControlProtocol.Message request(ClientGameTestContext context, ControlProtocol.Message message) {
			return blocking(context, () -> {
				message.write(this.socket.getOutputStream());
				return ControlProtocol.Message.read(this.socket.getInputStream(), 64 << 20);
			});
		}

		ControlProtocol.Message receive(ClientGameTestContext context) {
			return blocking(context, () -> ControlProtocol.Message.read(this.socket.getInputStream(), 64 << 20));
		}

		/** The next message, or null once the server has closed the connection. */
		ControlProtocol.Message receiveOrClosed(ClientGameTestContext context) {
			return blocking(context, () -> {
				InputStream in = this.socket.getInputStream();
				try {
					return ControlProtocol.Message.read(in, 64 << 20);
				} catch (IOException e) {
					return null;
				}
			});
		}

		void send(ControlProtocol.Message message) {
			try {
				message.write(this.socket.getOutputStream());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		void raw(byte[] bytes) {
			try {
				DataOutputStream out = new DataOutputStream(this.socket.getOutputStream());
				out.write(bytes);
				out.flush();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public void close() {
			try {
				this.socket.close();
			} catch (IOException ignored) {
				// Already closed by the server.
			}
		}
	}
}
