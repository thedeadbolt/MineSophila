package io.github.thedeadbolt.honeyfactory.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The control server's wire format; {@code packages/honey-factory/docs/control-protocol.md}
 * is its specification for client authors.
 *
 * <p>A message is a 4-byte big-endian header length, that many bytes of UTF-8 JSON (the
 * header), then the binary blobs whose byte lengths the header lists under {@code blobs},
 * in order. Requests and replies alternate strictly: one request, then one reply.
 */
public final class ControlProtocol {
	public static final int PROTOCOL_VERSION = 1;
	/** Message types, as the header's {@code type}. */
	public static final Set<String> TYPES = Set.of("hello", "reset", "step", "observation", "close", "error");
	private static final Set<String> COMMAND_FIELDS = Set.of("forward", "strafe", "turn_rate", "pitch_rate", "jump", "delay_ticks");

	private ControlProtocol() {
	}

	/** One message: its JSON header and its binary blobs. */
	public record Message(JsonObject header, List<byte[]> blobs) {
		/**
		 * Reads one message.
		 *
		 * @param maxBytes the largest header plus blobs accepted, in bytes
		 * @throws EOFException if the stream ends inside the message
		 * @throws IOException if the message is larger than {@code maxBytes} or its framing is invalid
		 */
		public static Message read(InputStream in, int maxBytes) throws IOException {
			DataInputStream data = new DataInputStream(in);
			int headerBytes = data.readInt();
			if (headerBytes < 2 || headerBytes > maxBytes) {
				throw new IOException("a header of " + headerBytes + " bytes is outside 2-" + maxBytes);
			}

			byte[] text = data.readNBytes(headerBytes);
			if (text.length != headerBytes) {
				throw new EOFException("the stream ended inside a " + headerBytes + "-byte header");
			}

			JsonObject header;
			try {
				header = JsonFields.parseObject(new String(text, StandardCharsets.UTF_8));
			} catch (ContractException e) {
				throw new IOException("the header is not a JSON object: " + e.getMessage(), e);
			}

			long total = headerBytes;
			List<byte[]> blobs = new ArrayList<>();
			JsonElement lengths = header.get("blobs");
			if (lengths != null) {
				if (!lengths.isJsonArray()) {
					throw new IOException("blobs must be an array of byte lengths");
				}

				for (JsonElement length : lengths.getAsJsonArray()) {
					int bytes = length.getAsInt();
					total += bytes;
					if (bytes < 0 || total > maxBytes) {
						throw new IOException("the message exceeds " + maxBytes + " bytes");
					}

					byte[] blob = data.readNBytes(bytes);
					if (blob.length != bytes) {
						throw new EOFException("the stream ended inside a " + bytes + "-byte blob");
					}

					blobs.add(blob);
				}
			}

			return new Message(header, List.copyOf(blobs));
		}

		/** Writes this message; the header's {@code blobs} is set to the blobs' lengths. */
		public void write(OutputStream out) throws IOException {
			JsonObject header = this.header.deepCopy();
			header.remove("blobs");
			if (!this.blobs.isEmpty()) {
				JsonArray lengths = new JsonArray();
				this.blobs.forEach(blob -> lengths.add(blob.length));
				header.add("blobs", lengths);
			}

			byte[] text = header.toString().getBytes(StandardCharsets.UTF_8);
			DataOutputStream data = new DataOutputStream(out);
			data.writeInt(text.length);
			data.write(text);
			for (byte[] blob : this.blobs) {
				data.write(blob);
			}

			data.flush();
		}
	}

	/**
	 * The header's message type.
	 *
	 * @throws ContractException at {@code /type} when it is missing or not a known type
	 */
	public static String type(JsonObject header) {
		JsonFields fields = JsonFields.of(header);
		String type = fields.string("type");
		if (!TYPES.contains(type)) {
			throw fields.error("type", "unknown message type '" + type + "'; expected one of " + TYPES.stream().sorted().toList());
		}

		return type;
	}

	/**
	 * A motor command from its JSON object; absent fields are 0 or false. Ranges are checked
	 * later against the motor limits.
	 *
	 * @throws ContractException naming the first field that is unknown or of the wrong type
	 */
	public static MotorCommand command(JsonObject json) {
		JsonFields fields = JsonFields.of(json);
		fields.rejectUnknown(COMMAND_FIELDS);
		double any = Double.MAX_VALUE;
		return new MotorCommand(
			fields.decimal("forward", -any, any, 0.0),
			fields.decimal("strafe", -any, any, 0.0),
			fields.decimal("turn_rate", -any, any, 0.0),
			fields.decimal("pitch_rate", -any, any, 0.0),
			fields.bool("jump", false),
			fields.integer32("delay_ticks", Integer.MIN_VALUE, Integer.MAX_VALUE, 0)
		);
	}

	/** An {@code error} reply header. */
	public static JsonObject error(String code, String message) {
		JsonObject header = new JsonObject();
		header.addProperty("type", "error");
		header.addProperty("code", code);
		header.addProperty("message", message);
		return header;
	}
}
