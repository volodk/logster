package com.logster;

import java.io.*;
import java.nio.file.*;

import static java.nio.file.StandardWatchEventKinds.*;

public class Discovery {

    private final Path dir;
    private final Indexer indexer;
    private final WatchService watcher;
    private final Thread watcherThread;

    public Discovery(final Path dir, final Indexer indexer) throws IOException {
        this.dir = dir;
        this.indexer = indexer;
        this.watcher = FileSystems.getDefault().newWatchService();
        this.watcherThread = new Thread(this::watch);
    }

    public void start() throws IOException {
        System.out.println("Starting Log Discovery");

        // watch for file modifications
        dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        watcherThread.start();

        // list and index existing files
        Files.list(dir).forEach(path -> {
            if (Files.isRegularFile(path)) {
                indexer.add(path.getFileName().toString());
            }
        });
    }

    public void stop() {
        System.out.println("Stopping Log Discovery");
        watcherThread.interrupt();
    }

    private void watch() {
        while (!Thread.currentThread().isInterrupted()) {

            final WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException e) {
                return;
            }

            for (final WatchEvent<?> event: key.pollEvents()) {
                final WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    continue;
                }

                final WatchEvent<Path> ev = (WatchEvent<Path>)event;
                final Path file = ev.context();

                // ensure that source file is a text log
                final Path child = dir.resolve(file);
                if (!Files.isRegularFile(child)) {
                    System.out.println("[DEBUG] Skipping. Not a regular file: " + file);
                    continue;
                }

                // dispatch file event
                final String filename = file.toString();
                if (kind == ENTRY_CREATE) {
                    System.out.println("[DEBUG] Creating log file: " + file);
                    indexer.add(filename);
                }
                if (kind == ENTRY_MODIFY) {
                    System.out.println("[DEBUG] Modified log file: " + file);
                    indexer.update(filename);
                }
                if (kind == ENTRY_DELETE) {
                    System.out.println("[DEBUG] Deleting log file: " + file);
                    indexer.remove(filename);
                }
            }

            boolean keyIsValid = key.reset();
            if (!keyIsValid) {
                break;
            }
        }
    }
}
