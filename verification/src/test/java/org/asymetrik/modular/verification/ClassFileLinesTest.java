package org.asymetrik.modular.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileLinesTest {

	private static final String SOURCE = """
		import java.util.function.Supplier;

		public class Sample {
			int twice(int n) {
				int a = n;
				Supplier<Integer> s = () -> {
					int b = a;
					return b * 2;
				};
				return s.get();
			}

			int twice(String n) {
				return n.length() * 2;
			}

			/** Only a comment */
			abstract static class Empty {
				abstract void nothing();
			}
		}
		""";

	@TempDir
	Path directory;

	@Test
	void the_lines_of_a_lambda_are_counted_with_the_overload_that_holds_it() throws IOException {
		ClassFileLines lines = ClassFileLines.of(compile("-g")).orElseThrow();

		// 3 (the implicit constructor), 5 to 8, 10 and 14: the closing brace of the lambda holds no code
		assertThat(lines.lines().cardinality()).isEqualTo(7);
		assertThat(lines.methods()).extracting(ClassFileLines.MethodLines::name,
				ClassFileLines.MethodLines::descriptor, method -> method.lines().cardinality())
			.containsExactly(
				tuple("<init>", "()V", 1),
				tuple("twice", "(I)I", 5),
				tuple("twice", "(Ljava/lang/String;)I", 1));
	}

	@Test
	void a_class_without_line_numbers_has_no_lines() throws IOException {
		assertThat(ClassFileLines.of(compile("-g:none"))).isEmpty();
	}

	@Test
	void a_method_without_line_numbers_is_left_out_and_the_others_counted() throws IOException {
		// As a framework adds a constructor to a bean class: here, the line numbers of twice(String) are removed
		byte[] generated = withoutLineNumbers(compile("-g"), "twice", "(Ljava/lang/String;)I");

		ClassFileLines lines = ClassFileLines.of(generated).orElseThrow();

		// Line 14 was the only line of twice(String)
		assertThat(lines.lines().cardinality()).isEqualTo(6);
		assertThat(lines.methods()).extracting(ClassFileLines.MethodLines::name,
				ClassFileLines.MethodLines::descriptor, method -> method.lines().cardinality())
			.containsExactly(
				tuple("<init>", "()V", 1),
				tuple("twice", "(I)I", 5));
	}

	@Test
	void a_class_without_code_has_no_line() throws IOException {
		compile("-g");
		ClassFileLines empty = ClassFileLines.of(Files.readAllBytes(directory.resolve("Sample$Empty.class")))
			.orElseThrow();

		// Its constructor holds its only line
		assertThat(empty.methods()).extracting(ClassFileLines.MethodLines::name).containsExactly("<init>");
	}

	@Test
	void what_is_not_a_class_file_has_no_lines() {
		assertThat(ClassFileLines.of(new byte[] {1, 2, 3})).isEqualTo(Optional.empty());
		assertThat(ClassFileLines.of(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE})).isEmpty();
	}

	@Test
	void parameters_are_written_as_in_java() {
		assertThat(ClassFileLines.parameters("()V")).isEqualTo("()");
		assertThat(ClassFileLines.parameters("(Ljava/util/List;[IZ[[Ljava/util/Map$Entry;J)V"))
			.isEqualTo("(List, int[], boolean, Map.Entry[][], long)");
	}

	/**
	 * The class file without the line numbers of one method. The ASM is the one ArchUnit shades: this test stops
	 * compiling if an upgrade moves it.
	 */
	private static byte[] withoutLineNumbers(byte[] classFile, String name, String descriptor) {
		ClassReader reader = new ClassReader(classFile);
		ClassWriter writer = new ClassWriter(0);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor, String signature,
											 String[] exceptions) {
				MethodVisitor method = super.visitMethod(access, methodName, methodDescriptor, signature, exceptions);
				if (!methodName.equals(name) || !methodDescriptor.equals(descriptor)) {
					return method;
				}
				return new MethodVisitor(Opcodes.ASM9, method) {
					@Override
					public void visitLineNumber(int line, Label start) {
						// Left out
					}
				};
			}
		}, 0);
		return writer.toByteArray();
	}

	private byte[] compile(String debug) throws IOException {
		Path source = directory.resolve("Sample.java");
		Files.writeString(source, SOURCE);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		int status = compiler.run(null, null, null, debug, "-d", directory.toString(), source.toString());
		assertThat(status).isZero();
		return Files.readAllBytes(directory.resolve("Sample.class"));
	}
}
