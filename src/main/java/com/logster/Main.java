package com.logster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws IOException, InterruptedException {

        final String logDir = System.getProperty("logs_dir");
        if (logDir == null) {
            System.out.println("Please specify logs location via -Dlogs_dir property");
            System.exit(-1);
        }

        final Path path = Path.of(logDir);
        validateAccess(path);

        final Indexer indexer = new Indexer(path);
        indexer.start();

        final Discovery discoveryService = new Discovery(path, indexer);
        discoveryService.start();

        final Server server = new Server(indexer, new FileReader(path));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Runtime.getRuntime().addShutdownHook(new Thread(discoveryService::stop));
        Runtime.getRuntime().addShutdownHook(new Thread(indexer::stop));

        Thread.currentThread().join();
    }

    private static void validateAccess(Path dir) {
        // basic validation & access check
        if (!Files.isDirectory(dir)){
            System.err.println("Not a folder: " + dir);
            System.exit(-1);
        }
        if (!Files.isReadable(dir)) {
            System.err.println("No read access for logs folder: " + dir);
            System.exit(-1);
        }
    }
}
