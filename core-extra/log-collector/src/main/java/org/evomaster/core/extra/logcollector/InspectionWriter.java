package org.evomaster.core.extra.logcollector;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the optional inspection output of the log collector. Neither file is needed for the
 * search itself; they are for monitoring, demonstration and interpreting results.
 *
 * <ul>
 *   <li><b>Templates file</b>: every template known so far, one per line as
 *       {@code log:<source>:<id>\t<template text>}. Rewritten whenever a template is added or
 *       its text changes.</li>
 *   <li><b>Trace file</b>: one JSON object per test evaluation, with the raw log lines credited
 *       to it and the templates they matched. Appended and flushed after every evaluation.</li>
 * </ul>
 *
 * <p>Both files are written as the search runs, so they can be followed live, and the latest
 * state stays on disk even if EvoMaster is killed.</p>
 */
public class InspectionWriter {

  private static final Logger log = LoggerFactory.getLogger(InspectionWriter.class);

  private final ObjectMapper mapper = new ObjectMapper();

  /** {@code null} if the templates file is disabled. */
  private final Path templatesFile;

  /** {@code null} if the trace file is disabled. */
  private final BufferedWriter trace;

  /** Target id → current template text, sorted so the file is easy to read and diff. */
  private final Map<String, String> templates = new TreeMap<>();

  /** Number of evaluations recorded so far; EvoMaster's own evaluation index is not visible to plugins. */
  private int eval = 0;

  /**
   * Existing files at the given paths are replaced, so no output of an earlier run is mistaken
   * for this one.
   *
   * @param templatesFile path of the templates file, or {@code null} to disable it
   * @param traceFile     path of the trace file, or {@code null} to disable it
   * @throws UncheckedIOException if a file cannot be created
   */
  public InspectionWriter(String templatesFile, String traceFile) {
    try {
      if (templatesFile != null) {
        this.templatesFile = Paths.get(templatesFile);
        createParentDirectories(this.templatesFile);
        Files.write(this.templatesFile, new byte[0]);
      } else {
        this.templatesFile = null;
      }

      if (traceFile != null) {
        Path tracePath = Paths.get(traceFile);
        createParentDirectories(tracePath);
        trace = Files.newBufferedWriter(tracePath, StandardCharsets.UTF_8);
      } else {
        trace = null;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create log collector inspection file", e);
    }
  }

  /**
   * Records one test evaluation. Failures to write are logged and do not affect the search.
   *
   * @param lines     the log lines credited to this evaluation
   * @param templates the {@code templates} array of the parser's {@code /parse} response
   */
  public void record(List<DockerLogFollower.Line> lines, JsonNode templates) {
    eval++;
    try {
      if (templatesFile != null) {
        updateTemplatesFile(templates);
      }
      if (trace != null) {
        appendTrace(lines, templates);
      }
    } catch (IOException e) {
      log.warn("Cannot write log collector inspection output: {}", e.getMessage());
    }
  }

  private void updateTemplatesFile(JsonNode hits) throws IOException {
    boolean changed = false;
    for (JsonNode t : hits) {
      String id = LogCollector.targetId(t.path("source").asText(), t.path("id").asText());
      String text = t.path("template").asText();
      // put() returns the previous text: null for a new template, different if it generalised.
      if (!text.equals(templates.put(id, text))) {
        changed = true;
      }
    }
    if (!changed) {
      return;
    }

    List<String> content = new ArrayList<>(templates.size());
    for (Map.Entry<String, String> e : templates.entrySet()) {
      content.add(e.getKey() + "\t" + e.getValue());
    }
    Files.write(templatesFile, content, StandardCharsets.UTF_8);
  }

  private void appendTrace(List<DockerLogFollower.Line> lines, JsonNode hits) throws IOException {
    ObjectNode entry = mapper.createObjectNode();
    entry.put("eval", eval);

    ArrayNode lineArray = entry.putArray("lines");
    for (DockerLogFollower.Line line : lines) {
      lineArray.addObject()
          .put("source", line.source())
          .put("text", line.text());
    }

    ArrayNode templateArray = entry.putArray("templates");
    for (JsonNode t : hits) {
      templateArray.addObject()
          .put("id", LogCollector.targetId(t.path("source").asText(), t.path("id").asText()))
          .put("template", t.path("template").asText())
          .put("count", t.path("count").asInt());
    }

    trace.write(mapper.writeValueAsString(entry));
    trace.newLine();
    trace.flush();
  }

  private static void createParentDirectories(Path file) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }
}
