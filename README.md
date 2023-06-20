# FHIR to Server

[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/miracum/kafka-fhir-to-server/badge)](https://api.securityscorecards.dev/projects/github.com/miracum/kafka-fhir-to-server)

> Reads FHIR Bundles from a Kafka topic and sends them to a FHIR server

Works great with the [MIRACUM Stream Processors chart](https://github.com/miracum/charts/tree/master/charts/stream-processors)!

## Container

Published as `ghcr.io/miracum/kafka-fhir-to-server`.

## Configuration

| Environment variable                                                         | Description                                                                                                                                                                                                                 | Default                    |
| ---------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| `BOOTSTRAP_SERVERS`                                                          | List of Kafka Bootstrap servers                                                                                                                                                                                             | `""`                       |
| `TOPIC`                                                                      | Kafka topic(s) to read FHIR resources from                                                                                                                                                                                  | `fhir-msg`                 |
| `FHIR_FILTER_EXPRESSION`                                                     | FHIR Path expression to filter resources by. Must return a boolean result.                                                                                                                                                  | `""`                       |
| `FHIR_OVERRIDE_BUNDLE_TYPE_WITH`                                             | A [FHIR Bundle type](https://www.hl7.org/fhir/valueset-bundle-type.html) to override the type of any received bundle with.                                                                                                  | `""`                       |
| `FHIR_AUTH_BASIC_ENABLED`                                                    | Enable HTTP Basic Auth when interacting with the FHIR server.                                                                                                                                                               | `false`                    |
| `FHIR_AUTH_BASIC_USERNAME`                                                   | HTTP Basic Auth username for the FHIR server.                                                                                                                                                                               | `""`                       |
| `FHIR_AUTH_BASIC_PASSWORD`                                                   | HTTP Basic Auth password for the FHIR server.                                                                                                                                                                               | `""`                       |
| `FHIR_HTTP_TIMEOUT_SECONDS`                                                  | HTTP client timeout in seconds when interacting with the FHIR server                                                                                                                                                        | `60`                       |
| `FHIR_MERGE_BATCHES_INTO_SINGLE_BUNDLE_ENABLED`                              | Enable merging bundles read as a batch from the input topic into a single topic composed of all individual resources.                                                                                                       | `false`                    |
| `FHIR_MERGE_BATCHES_INTO_SINGLE_BUNDLE_ENTRY_UNIQUENESS_FHIRPATH_EXPRESSION` | A FHIRPath expression evaluated against each bundle.entry. The resulting string represents the resource identity. If multiple entries have the same identity, only the one from the most recently received message is used. | `"resource.id.toString()"` |

See [application.yml](src/main/resources/application.yml) for more options.

## Observability

The application publishes useful Prometheus metrics at `/actuator/prometheus`.

## Development

### Setup Kafka

```sh
docker compose up
```

### Run the app

```sh
./gradlew :bootRun
```

### Kubernetes

#### Create a local KinD cluster

```sh
kind create cluster
```

#### Prerequisite: Install Strimzi & Kafka

```sh
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi-kafka-operator strimzi/strimzi-kafka-operator
kubectl apply -f k8s/kafka-cluster.yaml
```

#### Install a FHIR server and launch the fhir-to-server deployment

```sh
skaffold dev
```
