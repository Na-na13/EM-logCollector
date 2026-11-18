package org.evomaster.core.extra.logcollector;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows the log streams of the SUT's Docker containers and collects their output as lines.
 *
 * <p>Logs are read through the Docker API, using the client provided by Testcontainers, so no
 * Docker socket path or {@code docker} CLI is involved. No timestamps are used either: each
 * container's stream is followed from the moment of attaching, and incoming lines are buffered
 * until {@link #drainNewLines()} is called.</p>
 *
 * <p>Containers are looked up once, in the constructor. Containers started or recreated later
 * are not followed.</p>
 */
public class DockerLogFollower {

  private static final Logger log = LoggerFactory.getLogger(DockerLogFollower.class);

  private static final String COMPOSE_PROJECT_LABEL = "com.docker.compose.project";
  private static final String COMPOSE_SERVICE_LABEL = "com.docker.compose.service";

  /**
   * A single log line.
   *
   * @param source the Compose service (or container name) that logged the line
   * @param text   the line, without line terminator
   */
  public record Line(String source, String text) {}

  /** Lines received from all containers since the last drain. Guarded by {@code this}. */
  private List<Line> pending = new ArrayList<>();

  /**
   * Starts following the logs of all running containers of the given Docker Compose project.
   *
   * <p>The parser container started by Testcontainers has no Compose labels, so it is never
   * matched by the project filter.</p>
   *
   * @throws IllegalStateException if no running container belongs to the project
   */
  public DockerLogFollower(DockerClient docker, String composeProject) {
    List<Container> containers = docker.listContainersCmd()
        .withLabelFilter(Map.of(COMPOSE_PROJECT_LABEL, composeProject))
        .exec();

    if (containers.isEmpty()) {
      throw new IllegalStateException("No running Docker container found for Compose project '"
          + composeProject + "'. Check the project name, and that the SUT runs on the Docker"
          + " daemon used by Testcontainers.");
    }

    for (Container c : containers) {
      String source = sourceName(c);
      log.info("Following logs of container {} as source '{}'", c.getId(), source);

      // tail(0): ignore what was logged before attaching, only follow new output.
      docker.logContainerCmd(c.getId())
          .withStdOut(true)
          .withStdErr(true)
          .withFollowStream(true)
          .withTail(0)
          .exec(new ContainerCallback(source));
    }
  }

  /**
   * @return all complete lines received since the previous call, in arrival order
   */
  public synchronized List<Line> drainNewLines() {
    List<Line> lines = pending;
    pending = new ArrayList<>();
    return lines;
  }

  private synchronized void add(Line line) {
    pending.add(line);
  }

  /** Uses the Compose service name when available, otherwise the container name. */
  private static String sourceName(Container c) {
    String service = c.getLabels() == null ? null : c.getLabels().get(COMPOSE_SERVICE_LABEL);
    if (service != null && !service.isEmpty()) {
      return service;
    }
    String[] names = c.getNames();
    if (names != null && names.length > 0) {
      // Docker reports container names with a leading slash, e.g. "/my-container".
      return names[0].startsWith("/") ? names[0].substring(1) : names[0];
    }
    return c.getId();
  }

  /** Receives the raw frames of one container's log stream, on a docker-java thread. */
  private class ContainerCallback extends ResultCallback.Adapter<Frame> {

    private final String source;
    // stdout and stderr are buffered separately, so partial lines of the two streams do not mix.
    private final LineBuffer stdout = new LineBuffer();
    private final LineBuffer stderr = new LineBuffer();

    ContainerCallback(String source) {
      this.source = source;
    }

    @Override
    public void onNext(Frame frame) {
      LineBuffer buffer = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
      for (String text : buffer.append(frame.getPayload())) {
        add(new Line(source, text));
      }
    }

    @Override
    public void onError(Throwable throwable) {
      log.warn("Log stream of source '{}' failed: {}", source, throwable.getMessage());
    }

    @Override
    public void onComplete() {
      log.warn("Log stream of source '{}' ended; its container may have stopped", source);
    }
  }

  /**
   * Splits a byte stream into lines. Docker frames are not aligned with lines: a frame can hold
   * several lines or only part of one, and can even split a multi-byte UTF-8 character. Bytes
   * are therefore kept until a newline arrives, and only complete lines are decoded.
   */
  static class LineBuffer {

    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

    /** @return the lines completed by this chunk, without line terminators; blank lines are dropped */
    List<String> append(byte[] chunk) {
      List<String> lines = new ArrayList<>();
      for (byte b : chunk) {
        if (b != '\n') {
          partial.write(b);
          continue;
        }
        String line = new String(partial.toByteArray(), StandardCharsets.UTF_8);
        partial.reset();
        if (line.endsWith("\r")) {
          line = line.substring(0, line.length() - 1);
        }
        if (!line.isBlank()) {
          lines.add(line);
        }
      }
      return lines;
    }
  }
}
