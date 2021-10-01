# FHIR to Server

> Reads FHIR Bundles from a Kafka topic and sends them to a FHIR server

Works great with the [MIRACUM Stream Processors chart](https://github.com/miracum/charts/tree/master/charts/stream-processors)!

## Container

Published as `ghcr.io/miracum/kafka-fhir-to-server`.

## Configuration

| Environment variable             | Description                                                                                                                | Default    |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ---------- |
| `BOOTSTRAP_SERVERS`              | List of Kafka Bootstrap servers                                                                                            | `""`       |
| `TOPIC`                          | Kafka topic(s) to read FHIR resources from                                                                                 | `fhir-msg` |
| `FHIR_FILTER_EXPRESSION`         | FHIR Path expression to filter resources by. Must return a boolean result.                                                 | `""`       |
| `FHIR_OVERRIDE_BUNDLE_TYPE_WITH` | A [FHIR Bundle type](https://www.hl7.org/fhir/valueset-bundle-type.html) to override the type of any received bundle with. | `""`       |
| `FHIR_AUTH_BASIC_ENABLED`        | Enable HTTP Basic Auth when interacting with the FHIR server.                                                              | `false`    |
| `FHIR_AUTH_BASIC_USERNAME`       | HTTP Basic Auth username for the FHIR server.                                                                              | `""`       |
| `FHIR_AUTH_BASIC_PASSWORD`       | HTTP Basic Auth password for the FHIR server.                                                                              | `""`       |

See [application.yml](src/main/resources/application.yml) for more options.

## Observability

The application publishes useful Prometheus metrics at `/actuator/prometheus`. Tracing can be enabled by setting
`OPENTRACING_JAEGER_ENABLED=true`, see <https://github.com/opentracing-contrib/java-spring-jaeger> for more info.

## Development

### Setup Kafka

```sh
docker-compose -f docker-compose.dev.yml up
```

```sh
./gradlew :bootRun
```

### Kubernetes

#### Install Strimzi

```sh
helm repo add strimzi https://strimzi.io/charts/
helm repo update
helm install strimzi-operator strimzi/strimzi-kafka-operator
```

#### Create a cluster with TLS auth support, a FHIR server and launch the fhir-to-server deployment

```sh
skaffold dev
```
