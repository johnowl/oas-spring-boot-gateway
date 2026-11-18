# Container image of the CLI. See specs/012-cli-distribution.md.
# Build context: openapi-gateway-cli/, after ./mvnw -pl openapi-gateway-cli -am package.
FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918

COPY target/oas-gateway-validate.jar /opt/oas-gateway-validate/oas-gateway-validate.jar

# Pipelines mount the repository at /work, so paths in the output are relative to the repository root.
WORKDIR /work
USER 65532:65532

ENTRYPOINT ["java", "-jar", "/opt/oas-gateway-validate/oas-gateway-validate.jar"]
