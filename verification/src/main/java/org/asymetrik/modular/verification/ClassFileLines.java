/*
 * Copyright 2026 Asymetryk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asymetrik.modular.verification;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

/**
 * The source lines that hold code in a class file: the lines of its methods' {@code LineNumberTable}s, which javac
 * writes unless told not to ({@code -g:none}). Comments, blank lines and declarations without code are not in them.
 * <p>
 * The body of a lambda is a synthetic method of its own ({@code lambda$compute$0}): its lines are counted with the
 * method that holds the lambda. A method with code but no line numbers, such as a constructor that a framework adds
 * to the class, is left out: its lines are not known.
 *
 * @param lines the lines of the whole class file
 * @param methods its methods with code, with their lines, in the order of the class file
 */
record ClassFileLines(BitSet lines, List<MethodLines> methods) {

	/**
	 * A method with code.
	 *
	 * @param name its name, as in the class file: {@code <init>} for a constructor, {@code <clinit>} for a static
	 *             initializer
	 * @param descriptor its descriptor, such as {@code (Ljava/lang/String;I)V}
	 * @param lines its lines, and those of its lambdas
	 */
	record MethodLines(String name, String descriptor, BitSet lines) {}

	private static final int MAGIC = 0xCAFEBABE;
	private static final int ACC_SYNTHETIC = 0x1000;
	private static final String LAMBDA = "lambda$";

	/**
	 * Reads a class file. Empty when it is not one, or when none of its methods with code has line numbers: it was
	 * compiled without them.
	 */
	static Optional<ClassFileLines> of(byte[] classFile) {
		try {
			return read(new DataInputStream(new ByteArrayInputStream(classFile)));
		} catch (IOException | RuntimeException notAClassFile) {
			return Optional.empty();
		}
	}

	private record RawMethod(String name, String descriptor, boolean synthetic, BitSet lines) {}

	private static Optional<ClassFileLines> read(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			return Optional.empty();
		}
		// Minor and major versions
		in.skipNBytes(4);
		String[] utf8 = readConstantPool(in);
		// Access flags, this class, super class
		in.skipNBytes(6);
		in.skipNBytes(2L * in.readUnsignedShort());
		int fields = in.readUnsignedShort();
		for (int i = 0; i < fields; i++) {
			in.skipNBytes(6);
			skipAttributes(in);
		}
		List<RawMethod> raw = new ArrayList<>();
		boolean withoutLineNumbers = false;
		boolean withLineNumbers = false;
		int methods = in.readUnsignedShort();
		for (int i = 0; i < methods; i++) {
			int flags = in.readUnsignedShort();
			String name = utf8[in.readUnsignedShort()];
			String descriptor = utf8[in.readUnsignedShort()];
			int attributes = in.readUnsignedShort();
			for (int j = 0; j < attributes; j++) {
				String attribute = utf8[in.readUnsignedShort()];
				long length = Integer.toUnsignedLong(in.readInt());
				if (!"Code".equals(attribute)) {
					in.skipNBytes(length);
					continue;
				}
				Optional<BitSet> lines = readCode(in, utf8);
				if (lines.isEmpty()) {
					withoutLineNumbers = true;
				} else {
					withLineNumbers = true;
					raw.add(new RawMethod(name, descriptor, (flags & ACC_SYNTHETIC) != 0, lines.get()));
				}
			}
		}
		// Compiled without line numbers; a method that a framework added is only left out
		if (withoutLineNumbers && !withLineNumbers) {
			return Optional.empty();
		}
		return Optional.of(withLambdas(raw));
	}

	/** Adds the lines of each lambda to the method that holds it, and leaves out the synthetic methods. */
	private static ClassFileLines withLambdas(List<RawMethod> raw) {
		BitSet all = new BitSet();
		List<MethodLines> methods = new ArrayList<>();
		for (RawMethod method : raw) {
			all.or(method.lines());
			if (!method.synthetic()) {
				methods.add(new MethodLines(method.name(), method.descriptor(), (BitSet) method.lines().clone()));
			}
		}
		for (RawMethod lambda : raw) {
			if (!lambda.synthetic() || !lambda.name().startsWith(LAMBDA)) {
				continue;
			}
			holderOf(lambda, methods).ifPresent(holder -> holder.lines().or(lambda.lines()));
		}
		return new ClassFileLines(all, List.copyOf(methods));
	}

	/**
	 * The method that holds a lambda: {@code lambda$compute$0} is in a method named {@code compute}, {@code new} is a
	 * constructor and {@code static} the static initializer. Among overloads, the one whose lines surround the
	 * lambda's.
	 */
	private static Optional<MethodLines> holderOf(RawMethod lambda, List<MethodLines> methods) {
		String rest = lambda.name().substring(LAMBDA.length());
		int end = rest.lastIndexOf('$');
		if (end <= 0) {
			return Optional.empty();
		}
		String holder = switch (rest.substring(0, end)) {
			case "new" -> "<init>";
			case "static" -> "<clinit>";
			default -> rest.substring(0, end);
		};
		List<MethodLines> candidates = methods.stream().filter(method -> method.name().equals(holder)).toList();
		int first = lambda.lines().nextSetBit(0);
		return candidates.stream()
			.filter(method -> !method.lines().isEmpty() && first >= method.lines().nextSetBit(0)
				&& first <= method.lines().length() - 1)
			.findFirst()
			.or(() -> candidates.stream().findFirst());
	}

	/** Reads a {@code Code} attribute, after its length: its lines, empty when it has no line numbers. */
	private static Optional<BitSet> readCode(DataInputStream in, String[] utf8) throws IOException {
		// Max stack and max locals
		in.skipNBytes(4);
		in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
		// Exception table
		in.skipNBytes(8L * in.readUnsignedShort());
		BitSet lines = null;
		int attributes = in.readUnsignedShort();
		for (int i = 0; i < attributes; i++) {
			String name = utf8[in.readUnsignedShort()];
			long length = Integer.toUnsignedLong(in.readInt());
			if (!"LineNumberTable".equals(name)) {
				in.skipNBytes(length);
				continue;
			}
			if (lines == null) {
				lines = new BitSet();
			}
			int entries = in.readUnsignedShort();
			for (int j = 0; j < entries; j++) {
				// Start pc
				in.skipNBytes(2);
				lines.set(in.readUnsignedShort());
			}
		}
		return Optional.ofNullable(lines);
	}

	/** Reads the constant pool, and keeps its UTF-8 entries: the only ones the names need. */
	private static String[] readConstantPool(DataInputStream in) throws IOException {
		int count = in.readUnsignedShort();
		String[] utf8 = new String[count];
		for (int i = 1; i < count; i++) {
			int tag = in.readUnsignedByte();
			switch (tag) {
				case 1 -> utf8[i] = in.readUTF();
				// Class, String, MethodType, Module, Package
				case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
				// MethodHandle
				case 15 -> in.skipNBytes(3);
				// Integer, Float, Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
				case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
				// Long and Double take two entries
				case 5, 6 -> {
					in.skipNBytes(8);
					i++;
				}
				default -> throw new IOException("Unknown constant pool tag " + tag);
			}
		}
		return utf8;
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int attributes = in.readUnsignedShort();
		for (int i = 0; i < attributes; i++) {
			in.skipNBytes(2);
			in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
		}
	}

	/** {@code (Ljava/util/List;[IZ)V} as {@code (List, int[], boolean)}. */
	static String parameters(String descriptor) {
		List<String> types = new ArrayList<>();
		int i = descriptor.indexOf('(') + 1;
		while (i < descriptor.length() && descriptor.charAt(i) != ')') {
			int dimensions = 0;
			while (descriptor.charAt(i) == '[') {
				dimensions++;
				i++;
			}
			String type;
			char c = descriptor.charAt(i);
			if (c == 'L') {
				int end = descriptor.indexOf(';', i);
				String binary = descriptor.substring(i + 1, end);
				type = binary.substring(binary.lastIndexOf('/') + 1).replace('$', '.');
				i = end + 1;
			} else {
				type = switch (c) {
					case 'B' -> "byte";
					case 'C' -> "char";
					case 'D' -> "double";
					case 'F' -> "float";
					case 'I' -> "int";
					case 'J' -> "long";
					case 'S' -> "short";
					case 'Z' -> "boolean";
					default -> throw new IllegalArgumentException("Not a method descriptor: " + descriptor);
				};
				i++;
			}
			types.add(type + "[]".repeat(dimensions));
		}
		return "(" + String.join(", ", types) + ")";
	}
}
