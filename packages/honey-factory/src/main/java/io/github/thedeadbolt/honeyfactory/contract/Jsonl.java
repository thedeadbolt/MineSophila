package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON Lines as the contract requires: UTF-8 without a byte-order mark, one complete JSON
 * object per line and {@code \n} after every record, including the last.
 */
public final class Jsonl {
	private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();

	private Jsonl() {
	}

	public static String line(JsonObject record) {
		return COMPACT.toJson(record) + "\n";
	}

	public static byte[] write(List<JsonObject> records) {
		StringBuilder out = new StringBuilder();
		records.forEach(record -> out.append(line(record)));
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** A single pretty-printed JSON document ending with a newline, as used for {@code dataset.json}. */
	public static byte[] document(JsonObject object) {
		return (PRETTY.toJson(object) + "\n").getBytes(StandardCharsets.UTF_8);
	}

	public static List<JsonObject> read(byte[] bytes, String fileName) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException e) {
			throw new ContractException(fileName, "is not valid UTF-8");
		}

		if (text.startsWith("﻿")) {
			throw new ContractException(fileName, "must not start with a byte-order mark");
		}

		if (!text.isEmpty() && !text.endsWith("\n")) {
			throw new ContractException(fileName, "every record, including the last, must end with \\n");
		}

		List<JsonObject> records = new ArrayList<>();
		String[] lines = text.isEmpty() ? new String[0] : text.substring(0, text.length() - 1).split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			try {
				records.add(JsonFields.parseObject(lines[i]));
			} catch (ContractException e) {
				throw new ContractException(fileName + ":" + (i + 1), e.getMessage());
			}
		}

		return records;
	}
}
