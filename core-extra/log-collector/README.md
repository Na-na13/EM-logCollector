# Log Collector

An `AdditionalTargetCollector` plugin that uses Docker container log templates as extra fitness targets for EvoMaster.

After each test, it collects logs from the SUT containers, applies the [Drain](https://github.com/logpai/logparser) log parsing algorithm to detect novel log event templates, and reports them as additional coverage targets.

## Prerequisites

- Docker must be running on the host machine
- The SUT must be running as Docker containers (e.g. via Docker Compose)

## Usage

Enable the plugin with the EvoMaster flag:

```
--enableLogCollector=true
```

To restrict log collection to a specific Docker Compose project, set the environment variable before running EvoMaster:

```
export LOG_COLLECTOR_PROJECT=your-compose-project-name
java -jar evomaster.jar --enableLogCollector=true ...
```

If `LOG_COLLECTOR_PROJECT` is not set, logs from all running containers are collected.

## How it works

1. LogCollector starts the `nanarei/evolog-parser` Docker image as a persistent background daemon
2. Before each test, it records the current timestamp
3. After each test, it sends the time window (`start–end`) to the daemon via stdin
4. The daemon fetches container logs for that window, runs Drain, and writes unique templates to `templates/unique-templates.txt`
5. LogCollector reads the file and reports each novel template as a fitness target with descriptor `log:<service>:<drainId>`

## Known limitations

- Requires Docker socket access (`/var/run/docker.sock`) — not supported on Windows with native Docker
- Currently wired to `EnterpriseFitness` only; `BlackBoxRestFitness` wiring is pending
- Parser image is hosted on a personal DockerHub account (`nanarei/evolog-parser`) — should move to an EvoMaster organisation account
