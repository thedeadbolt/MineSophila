package io.github.thedeadbolt.honeyfactory.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlProtocolTest {
	private static final int MAX = 1 << 20;

	private static byte[] bytes(ControlProtocol.Message message) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		message.write(out);
		return out.toByteArray();
	}

	@Test
	void observationWithBlobsRoundTrips() throws IOException {
		JsonObject header = JsonFields.parseObject("{\"type\": \"observation\", \"index\": 4, \"senses\": {\"pitch\": 12.5}}");
		byte[] rgb = {1, 2, 3, 4, 5, 6};
		byte[] mask = {0, (byte) 255};
		byte[] wire = bytes(new ControlProtocol.Message(header, List.of(rgb, mask)));
		ControlProtocol.Message read = ControlProtocol.Message.read(new ByteArrayInputStream(wire), MAX);
		assertEquals("observation", ControlProtocol.type(read.header()));
		assertEquals(4, read.header().get("index").getAsInt());
		assertEquals(2, read.blobs().size());
		assertArrayEquals(rgb, read.blobs().get(0));
		assertArrayEquals(mask, read.blobs().get(1));
		assertArrayEquals(wire, bytes(read), "writing what was read gives the same bytes");
	}

	@Test
	void oversizedTruncatedAndUnknownMessagesAreRefused() throws IOException {
		byte[] wire = bytes(new ControlProtocol.Message(JsonFields.parseObject("{\"type\": \"close\", \"padding\": \"" + "x".repeat(200) + "\"}"), List.of()));
		IOException tooBig = assertThrows(IOException.class, () -> ControlProtocol.Message.read(new ByteArrayInputStream(wire), 100));
		assertTrue(tooBig.getMessage().contains("100"), tooBig.getMessage());
		assertThrows(EOFException.class, () -> ControlProtocol.Message.read(new ByteArrayInputStream(Arrays.copyOf(wire, wire.length - 5)), MAX));
		ContractException unknown = assertThrows(ContractException.class, () -> ControlProtocol.type(JsonFields.parseObject("{\"type\": \"teleport\"}")));
		assertEquals("/type", unknown.path());
	}

	@Test
	void commandsParseWithDefaultsAndNameBadFields() {
		MotorCommand command = ControlProtocol.command(JsonFields.parseObject("{\"forward\": 0.5, \"turn_rate\": -3, \"jump\": true}"));
		assertEquals(new MotorCommand(0.5, 0.0, -3.0, 0.0, true, 0), command);
		assertEquals(command, ControlProtocol.command(command.toJson()));
		assertEquals("/turn_rate", assertThrows(ContractException.class, () -> ControlProtocol.command(JsonFields.parseObject("{\"turn_rate\": \"fast\"}"))).path());
		assertEquals("/sideways", assertThrows(ContractException.class, () -> ControlProtocol.command(JsonFields.parseObject("{\"sideways\": 1}"))).path());
	}
}
