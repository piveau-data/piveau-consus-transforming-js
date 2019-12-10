# transforming js
Microservice for transforming data in a pipe.

The service is based on the [pipe-connector](https://github.com/piveau-data/piveau-pipe-connector) library. Any configuration applicable for the pipe-connector can also be used for this service.

## Table of Contents
1. [Build](#build)
1. [Run](#run)
1. [Docker](#docker)
1. [Configuration](#configuration)
    1. [Pipe](#pipe)
    1. [Data Info Object](#data-info-object)
    1. [Environment](#environment)
    1. [Logging](#logging)
1. [License](#license)

## Build

Requirements:
 * Git
 * Maven 3
 * Java 11

```bash
$ git clone https://github.com/piveau-data/piveau-consus-transforming-js.git
$ cd piveau-consus-transforming-js
$ mvn package
```
 
## Run

```bash
$ java -jar target/piveau-transforming-js-far.jar
```

## Docker

Build docker image:
```bash
$ docker build -t piveau/piveau-transforming-js .
```

Run docker image:
```bash
$ docker run -it -p 8080:8080 piveau/piveau-tranforming-js
```

## Configuration

### Pipe
The transformer can be configured through the config object as part of the segment body of the pipe.
It allows the configuration of either embedding the script directly in the config object or passing a references to a git repository:

```json
{
  "scriptType": "",
  "repository": {
    "uri": "",
    "branch": "",
    "username": "",
    "token": "",
    "script": ""
  },
  "script": ""
}
```
If `scriptType` is either `embedded`, which means the script is contained in the `script` field. Or has the value `repository` which indicates a script in a git repository described in more details in the `repository` field.

### Environment
See also [pipe-connector](https://github.com/piveau-data/piveau-pipe-connector)

| Variable| Description | Default Value |
| :--- | :--- | :--- |
| `PIVEAU_REPOSITORY_DEFAULT_BRANCH` | The default branch to use when not specified in the pipe configuration | `master` |

### Logging
See [logback](https://logback.qos.ch/documentation.html) documentation for more details

| Variable| Description | Default Value |
| :--- | :--- | :--- |
| `PIVEAU_PIPE_LOG_APPENDER` | Configures the log appender for the pipe context | `STDOUT` |
| `PIVEAU_LOGSTASH_HOST`            | The host of the logstash service | `logstash` |
| `PIVEAU_LOGSTASH_PORT`            | The port the logstash service is running | `5044` |
| `PIVEAU_PIPE_LOG_PATH`     | Path to the file for the file appender | `logs/piveau-pipe.%d{yyyy-MM-dd}.log` |
| `PIVEAU_PIPE_LOG_LEVEL`    | The log level for the pipe context | `INFO` |
| `PIVEAU_LOG_LEVEL`    | The general log level for the `io.piveau` package | `INFO` |

## License

[Apache License, Version 2.0](LICENSE.md)
