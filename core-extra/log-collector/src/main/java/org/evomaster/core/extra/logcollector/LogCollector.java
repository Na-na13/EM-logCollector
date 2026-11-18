package org.evomaster.core.extra.logcollector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.evomaster.core.extra.shared.AdditionalTargetCollector;
import org.evomaster.core.extra.shared.TargetInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * An {@link AdditionalTargetCollector} that uses log-template coverage as an extra fitness signal.
 *
 * <p>The SUT's Docker container logs are followed by a {@link DockerLogFollower}. After each test,
 * the lines logged during that test are sent to the parser service, which reduces them to log
 * templates with Drain. Each template is reported as a target {@code log:<source>:<drainId>}.</p>
 *
 * <p>Lines are attributed to tests without timestamps: everything logged between the start and
 * the end of a test belongs to that test, and lines logged between tests (e.g. while the SUT is
 * reset) are discarded.</p>
 *
 * <p>The parser runs in a Docker container started with Testcontainers, which also removes it
 * when the JVM exits. See {@code DESIGN.md} in this module for the full design.</p>
 *
 * <p>Note: {@code LoggerFactory.getLogger()} is used here instead of {@code LoggingUtil.getInfoLogger()}
 * because this module ({@code core-extra}) cannot depend on {@code core} without creating a circular
 * dependency. Informational log messages will therefore not appear in EvoMaster's console output.</p>
 */
public class LogCollector implements AdditionalTargetCollector {

  private static final Logger log = LoggerFactory.getLogger(LogCollector.class);

  /** Port the parser's HTTP server listens on inside its container. Part of the image contract. */
  private static final int PARSER_PORT = 8080;

  private static final Duration PARSE_TIMEOUT = Duration.ofSeconds(60);

  private final ObjectMapper mapper = new ObjectMapper();

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private final DockerLogFollower follower;

  /** Kept so the container's lifetime is tied to this collector; Testcontainers removes it on JVM exit. */
  private final GenericContainer<?> parser;

  private final URI parseUri;

  /** {@code null} when no inspection output is enabled. */
  private final InspectionWriter writer;

  /**
   * Starts following the SUT's container logs and starts the parser container.
   *
   * @param composeProject Docker Compose project whose containers are followed
   * @param parserImage    Docker image of the parser service
   * @param templatesFile  path of the templates file, or {@code null} to not write it
   * @param traceFile      path of the trace file, or {@code null} to not write it
   * @throws IllegalStateException if no container of the project is running
   * @throws RuntimeException      if the parser container cannot be started
   */
  // "resource": the parser container is deliberately never closed here; it must run for the
  // whole search, and Testcontainers removes it when the JVM exits.
  @SuppressWarnings("resource")
  public LogCollector(String composeProject, String parserImage, String templatesFile, String traceFile) {
    // Done first, so a wrong project name fails fast, before a possibly long image pull.
    follower = new DockerLogFollower(DockerClientFactory.instance().client(), composeProject);

    parser = new GenericContainer<>(DockerImageName.parse(parserImage))
        .withExposedPorts(PARSER_PORT)
        .waitingFor(Wait.forHttp("/health"))
        .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("parser"));
    parser.start();
    parseUri = URI.create("http://" + parser.getHost() + ":" + parser.getMappedPort(PARSER_PORT) + "/parse");
    log.info("Log parser started from image {} at {}", parserImage, parseUri);

    writer = (templatesFile != null || traceFile != null)
        ? new InspectionWriter(templatesFile, traceFile)
        : null;
  }

  /**
   * The fitness target id of a log template, e.g. {@code log:oauth2-client:17}.
   * Uses the stable Drain cluster id, never the template text, which can change during the run.
   */
  static String targetId(String source, String drainId) {
    return "log:" + source + ":" + drainId;
  }

  @Override
  public void goingToStartExecutingNewTest() {
    // Lines logged since the previous test ended were not caused by any test, e.g. SUT reset output.
    int discarded = follower.drainNewLines().size();
    log.debug("Discarded {} log lines logged between tests", discarded);
  }

  @Override
  public void reportActionIndex(int actionIndex) {
    // Not used: targets are reported per test, so TargetInfo uses -1 as action index.
  }

  @Override
  public List<TargetInfo> testFinishedCollectResult() {
    List<DockerLogFollower.Line> lines = follower.drainNewLines();

    JsonNode templates;
    if (lines.isEmpty()) {
      templates = mapper.createArrayNode();
    } else {
      try {
        templates = parse(lines);
      } catch (IOException e) {
        log.warn("Log parser request failed, no log targets for this test: {}", e.getMessage());
        return Collections.emptyList();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Collections.emptyList();
      }
    }

    if (writer != null) {
      writer.record(lines, templates);
    }

    List<TargetInfo> targets = new ArrayList<>(templates.size());
    for (JsonNode t : templates) {
      targets.add(new TargetInfo(targetId(t.path("source").asText(), t.path("id").asText()), 1.0, -1));
    }
    return targets;
  }

  /**
   * Sends the lines to {@code POST /parse}.
   *
   * @return the {@code templates} array of the response
   * @throws IOException if the request fails, the status is not 200, or the response has no {@code templates} array
   */
  private JsonNode parse(List<DockerLogFollower.Line> lines) throws IOException, InterruptedException {
    ObjectNode body = mapper.createObjectNode();
    ArrayNode lineArray = body.putArray("lines");
    for (DockerLogFollower.Line line : lines) {
      lineArray.addObject()
          .put("source", line.source())
          .put("text", line.text());
    }

    HttpRequest request = HttpRequest.newBuilder(parseUri)
        .timeout(PARSE_TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();

    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode templates = mapper.readTree(response.body()).path("templates");
    if (!templates.isArray()) {
      throw new IOException("response has no 'templates' array");
    }
    return templates;
  }
}
