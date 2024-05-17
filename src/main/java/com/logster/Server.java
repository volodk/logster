package com.logster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;

public class Server {

    private final Indexer indexer;
    private final FileReader fileReader;
    private final HttpServer server;
    private final ExecutorService threadpool;

    public Server(final Indexer indexer, final FileReader fileReader) throws IOException {
        this.indexer = indexer;
        this.fileReader = fileReader;

        threadpool = Executors.newCachedThreadPool();

        server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/list", new ListHandler());
        server.createContext("/tail", new TailHandler());
        server.setExecutor(threadpool);
    }

    public void start() throws IOException {
        System.out.println("Starting Server");
        server.start();
    }

    public void stop() {
        System.out.println("Stopping Server");
        server.stop(0);
        threadpool.shutdown();
    }

    class ListHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {

            final StringBuilder builder = new StringBuilder();
            final List<String> files = indexer.getIndexedFiles();
            for (String file : files) {
                builder.append(
                    String.format("%s : %s\n", file, indexer.getIndexedPositions(file).status())
                );
            }
            final String response = builder.toString();
            exchange.sendResponseHeaders(200, response.length());
            try (final OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    class TailHandler implements HttpHandler {

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            final String query = exchange.getRequestURI().getQuery();

            // params presence check
            final Map<String, String> params = getParamMap(query);
            if (!params.containsKey("log") || !params.containsKey("n")) {
                final String response = "Bad request";
                exchange.sendResponseHeaders(400, response.length());
                try (final OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // simple validation check
            try {
                long n = Long.parseLong(params.get("n"));
                if (n < 0) {
                    final String response = "Bad request";
                    exchange.sendResponseHeaders(400, response.length());
                    try (final OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                }
            } catch (NumberFormatException e) {
                final String response = "Bad request";
                exchange.sendResponseHeaders(400, response.length());
                try (final OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // log presence check
            final String filename = params.get("log");
            if (indexer.getIndexedPositions(filename) == null) {
                final String response = "File Not Found";
                exchange.sendResponseHeaders(404, response.length());
                try (final OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // readiness check
            if (indexer.getIndexedPositions(filename).status() == Indexer.Status.INIT) {
                final String response = "File not ready. Try again later";
                exchange.sendResponseHeaders(204, response.length());
                try (final OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // actual call
            final Indexer.PositionList positionList = indexer.getIndexedPositions(filename);
            final long lines = Long.parseLong(params.get("n"));

            if (lines > positionList.positions().size()) {
                // range is outside of index, reading file directly
                try (final OutputStream os = exchange.getResponseBody()) {
                    exchange.sendResponseHeaders(200, 0);
                    fileReader.readSlow(filename, lines, os);
                }
            } else {
                // range is in index, do positional read
                try (final OutputStream os = exchange.getResponseBody()) {
                    long pos = indexer.getStartingPosition(filename, lines);
                    exchange.sendResponseHeaders(200, 0);
                    fileReader.readFast(filename, pos, os);
                }
            }
        }

        private static Map<String, String> getParamMap(String query) {
            if (query == null || query.isEmpty()) return emptyMap();
            return Stream.of(query.split("&"))
                    .filter(s -> !s.isEmpty())
                    .map(kv -> kv.split("=", 2))
                .collect(toMap(pair -> pair[0], pair -> pair[1]));
        }
    }
}