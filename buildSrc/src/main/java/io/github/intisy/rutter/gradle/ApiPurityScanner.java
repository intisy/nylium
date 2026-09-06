package io.github.intisy.rutter.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public final class ApiPurityScanner {

    private static final String FORBIDDEN = "net/minecraft/";

    private ApiPurityScanner() {
    }

    public static List<String> scan(byte[] classFile) {
        final List<String> findings = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {

            private String owner;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                owner = name;
                check("class " + name + " supertype", superName);
                check("class " + name + " signature", signature);
                if (interfaces != null) {
                    for (String each : interfaces) {
                        check("class " + name + " interface", each);
                    }
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (isPublicSurface(access)) {
                    check("field " + owner + "." + name, descriptor);
                    check("field " + owner + "." + name + " signature", signature);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (isPublicSurface(access)) {
                    check("method " + owner + "." + name, descriptor);
                    check("method " + owner + "." + name + " signature", signature);
                    if (exceptions != null) {
                        for (String each : exceptions) {
                            check("method " + owner + "." + name + " throws", each);
                        }
                    }
                }
                return null;
            }

            private boolean isPublicSurface(int access) {
                return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
            }

            private void check(String where, String text) {
                if (text != null && text.contains(FORBIDDEN)) {
                    findings.add(where + " references " + FORBIDDEN);
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return findings;
    }
}
