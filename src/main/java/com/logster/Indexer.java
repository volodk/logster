package com.logster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class Indexer {

    public static final int HEURISTIC_LIMIT = 1024;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private final Path dir;
    private final ExecutorService fileIndexers;
    private final BlockingQueue<String> fileIndexingQueue;

    public enum Status {INIT, READY}
    public record PositionList(Status status, LinkedList<Long> positions) {}
    private final ConcurrentHashMap<String, PositionList> filenameToLinePositions;

    public Indexer(final Path dir) {
        this.dir = dir;
        this.filenameToLinePositions = new ConcurrentHashMap<>();
        this.fileIndexingQueue = new LinkedBlockingQueue<>();
        this.fileIndexers = Executors.newWorkStealingPool(CPU_COUNT);
    }

    public void start() {
        System.out.println("Starting Log Indexer");
        for (int i = 0; i < CPU_COUNT; i++) {
            fileIndexers.submit(this::indexLogFile);
        }
    }

    public void stop() {
        System.out.println("Stopping Log Indexer");
        fileIndexers.shutdown();
    }

    public List<String> getIndexedFiles() {
        return filenameToLinePositions.keySet().stream().sorted().toList();
    }

    public PositionList getIndexedPositions(String filename) {
        return filenameToLinePositions.get(filename);
    }

    public void add(String filename) {
        filenameToLinePositions.put(filename, new PositionList(Status.INIT, new LinkedList<>()));
        fileIndexingQueue.offer(filename);
    }

    public void update(String filename) {
        if (filenameToLinePositions.containsKey(filename)) {
            fileIndexingQueue.offer(filename);
        }
    }

    public void remove(String filename) {
        filenameToLinePositions.remove(filename);
    }

    public long getStartingPosition(String filename, long lastLines) {
        List<Long> positions = filenameToLinePositions.get(filename).positions();
        int i = 0;
        while (i + lastLines < positions.size()) {
            i++;
        }
        return positions.get(i);
    }

    private void indexLogFile() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final String filename = fileIndexingQueue.take();
                final PositionList pos = filenameToLinePositions.get(filename);
                if (pos == null) {
                    System.out.println("[DEBUG] File Not Found, file: " + filename);
                    continue;
                }

                switch (pos.status()) {
                    case INIT -> {
                        System.out.println("[DEBUG] Creating positions index, file: " + filename);
                        createLogLinesPositionIndex(filename);
                        final LinkedList<Long> positions = filenameToLinePositions.get(filename).positions();
                        filenameToLinePositions.put(filename, new PositionList(Status.READY, positions));
                    }
                    case READY -> {
                        System.out.println("[DEBUG] Refreshing positions index, file: " + filename);
                        refreshIndexFromLastPosition(filename);
                    }
                    default -> throw new RuntimeException("Unexpected status: " + pos.status());
                }

            } catch (InterruptedException e) {
                System.out.println("[DEBUG] Log indexing thread was interrupted");
                break;
            }
        }
        System.out.println("[DEBUG] Closing log indexing thread");
    }

    private void refreshIndexFromLastPosition(final String filename) {
        Deque<Long> positions = filenameToLinePositions.get(filename).positions();
        reindexFromOffset(filename, positions.getLast());
    }

    private void createLogLinesPositionIndex(final String filename) {
        reindexFromOffset(filename, 0);
    }

    private void reindexFromOffset(final String filename, long offset) {
        try {
            final Deque<Long> positions = filenameToLinePositions.get(filename).positions();
            final Path log = dir.resolve(filename);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8))) {

                in.skip(offset);

                long newLinePosition = offset;
                // recording first line, otherwise it was recorded before
                if (offset == 0) {
                    positions.addLast(newLinePosition);
                }

                int r;
                boolean cr = false;
                while ( (r = in.read()) != -1) {
                    newLinePosition++;
                    char ch = (char) r;
                    if (ch == '\n') {
                        cr = true;
                    } else {
                        if (cr) {
                            cr = false;
                            positions.addLast(newLinePosition);
                        }
                    }
                    // keep in log line index only last X positions
                    // remove from the head
                    if (positions.size() > HEURISTIC_LIMIT) {
                        positions.pollFirst();
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[DEBUG] IO exception, file: " + filename);
        }
    }
}
