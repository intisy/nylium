package io.github.intisy.rutter.gradle;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApiPurityScanner {

    private static final String FORBIDDEN = "net/minecraft/";

    private ApiPurityScanner() {
    }

    public static List<String> scan(Path jarFile) throws IOException {
        List<String> findings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    findings.addAll(scan(readFully(stream)));
                }
            }
        }
        return findings;
    }

    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    public static List<String> scan(byte[] classFile) {
        final List<String> findings = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {

            private String owner;
            private boolean publicSurface;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                owner = name;
                publicSurface = isPublicSurface(access);
                if (!publicSurface) {
                    return;
                }
                check("class " + name + " supertype", superName);
                check("class " + name + " signature", signature);
                if (interfaces != null) {
                    for (String each : interfaces) {
                        check("class " + name + " interface", each);
                    }
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (publicSurface) {
                    check("class " + owner + " annotation", descriptor);
                }
                return null;
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                          String descriptor, boolean visible) {
                if (publicSurface) {
                    check("class " + owner + " type annotation", descriptor);
                }
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (!isPublicSurface(access)) {
                    return null;
                }
                check("field " + owner + "." + name, descriptor);
                check("field " + owner + "." + name + " signature", signature);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        check("field " + owner + "." + name + " annotation", annotationDescriptor);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                  String annotationDescriptor, boolean visible) {
                        check("field " + owner + "." + name + " type annotation", annotationDescriptor);
                        return null;
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!isPublicSurface(access)) {
                    return null;
                }
                check("method " + owner + "." + name, descriptor);
                check("method " + owner + "." + name + " signature", signature);
                if (exceptions != null) {
                    for (String each : exceptions) {
                        check("method " + owner + "." + name + " throws", each);
                    }
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        check("method " + owner + "." + name + " annotation", annotationDescriptor);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                  String annotationDescriptor, boolean visible) {
                        check("method " + owner + "." + name + " type annotation", annotationDescriptor);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor,
                                                                       boolean visible) {
                        check("method " + owner + "." + name + " parameter annotation", annotationDescriptor);
                        return null;
                    }
                };
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
