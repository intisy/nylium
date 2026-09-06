package io.github.intisy.rutter.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public abstract class ApiPurityTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getJar();

    @TaskAction
    public void check() throws IOException {
        List<String> findings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(getJar().get().getAsFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = stream.read(chunk)) >= 0) {
                        buffer.write(chunk, 0, read);
                    }
                    findings.addAll(ApiPurityScanner.scan(buffer.toByteArray()));
                }
            }
        }
        if (!findings.isEmpty()) {
            throw new GradleException("rutter-api must not expose net.minecraft types:\n  "
                    + String.join("\n  ", findings));
        }
    }
}
