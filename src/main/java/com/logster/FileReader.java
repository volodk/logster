package com.logster;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public class FileReader {

    private final Path dir;

    public FileReader(final Path dir) {
        this.dir = dir;
    }

    // positional read
    public void readFast(final String filename, long startingPosition, OutputStream out) throws IOException {
        final Path log = dir.resolve(filename);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8));
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {

            br.skip(startingPosition > 0 ? startingPosition - 1 : 0);

            char[] buff = new char[1024];
            int len = br.read(buff);
            while (len != -1) {
                bw.write(buff, 0, len);
                len = br.read(buff);
            }
        }
    }

    public void readSlow(final String filename, long lastLines, OutputStream out) throws IOException {
        final Path log = dir.resolve(filename);
        long totalLines;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8))){
            totalLines = br.lines().count();
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8));
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            br.lines().skip(totalLines - lastLines > 0 ? totalLines - lastLines : 0).forEach(line -> {
                try {
                    bw.write(line);
                    bw.newLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
