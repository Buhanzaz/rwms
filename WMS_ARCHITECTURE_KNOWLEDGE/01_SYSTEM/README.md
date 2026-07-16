# 01 System

## Runtime Stack

Legacy project: `wms-panel-old`.

- Build: Gradle Groovy DSL, root project name `wms-panel`.
- Java: toolchain Java 21.
- Framework: Jmix `2.8.1`.
- UI: Jmix FlowUI on Vaadin, PWA enabled, server push enabled.
- Persistence: Jmix Data with EclipseLink.
- Default database: file HSQLDB at `jdbc:hsqldb:file:.jmix/hsqldb/wmspanel`.
- Test database: separate HSQLDB configured in `src/test/resources/application-test.properties`.
- REST: Spring Boot Web.
- Messaging: Spring AMQP/RabbitMQ.
- Object storage: MinIO, with local filesystem fallback.
- AI: Spring AI OpenAI-compatible configuration pointed at Mistral by default.
- Excel import: Apache POI.

Evidence:

- `wms-panel-old/build.gradle`
- `wms-panel-old/settings.gradle`
- `wms-panel-old/gradle.properties`
- `wms-panel-old/src/main/resources/application.properties`
- `wms-panel-old/WmsPanelApplication.java`

## Dependencies

Main Jmix dependencies:

- `io.jmix.core:jmix-core-starter`
- `io.jmix.data:jmix-eclipselink-starter`
- `io.jmix.security:jmix-security-starter`
- `io.jmix.security:jmix-security-flowui-starter`
- `io.jmix.security:jmix-security-data-starter`
- `io.jmix.localfs:jmix-localfs-starter`
- `io.jmix.flowui:jmix-flowui-starter`
- `io.jmix.flowui:jmix-flowui-data-starter`
- `io.jmix.datatools:jmix-datatools-starter`
- `io.jmix.translations:jmix-translations-ru`

Main non-Jmix dependencies:

- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-amqp`
- `org.springframework.ai:spring-ai-openai:1.1.8`
- `io.minio:minio:8.6.0`
- `org.apache.poi:poi-ooxml:5.4.1`
- `org.hsqldb:hsqldb`

Hilla, Hilla dev, and Vaadin Copilot are excluded from implementation configuration.

## Application Configuration

Core config:

- `main.datasource.*` controls the primary datasource.
- `main.liquibase.change-log=dev/buhanzaz/wmspanel/liquibase/changelog.xml`
- `jmix.ui.login-view-id=LoginView`
- `jmix.ui.main-view-id=MainView`
- `jmix.ui.menu-config=dev/buhanzaz/wmspanel/menu.xml`
- `jmix.ui.composite-menu=true`
- Default dev login values are `admin` / `admin`.
- Available locales: `ru,en`.

Media config:

- `repair.media.local-root=.media/rental-item-events`
- `repair.media.object-prefix=repair-estimates`
- `repair.media.minio-enabled=true`
- default MinIO endpoint `http://localhost:9000`
- bucket `repair-media`
- queue names `repair.media.process` and `repair.media.processed`
- variants: preview 1280 px, thumb 320 px, tiny 96 px long edge.

AI config:

- `spring.ai.openai.chat.base-url=https://api.mistral.ai/v1`
- `spring.ai.openai.chat.options.model=mistral-small-latest`
- API key comes from `MISTRAL_API_KEY` or Spring property.

## Modules

There is no multi-module Gradle build. The legacy system has:

- Java/Jmix monolith under `src/main/java/dev/buhanzaz/wmspanel`.
- Resource descriptors, Liquibase, messages, and menu XML under `src/main/resources/dev/buhanzaz/wmspanel`.
- Go photo worker under `photo-worker-go`.
- Docker Compose local infra under `docker-compose.yml`.

## Local Infrastructure

`docker-compose.yml` defines:

- MinIO service with console port 9001 and API port 9000.
- `minio-init`, creating bucket `repair-media`.
- RabbitMQ with management UI.
- `photo-worker-go`, consuming and publishing RabbitMQ messages and writing MinIO variants.

## Tests As Behavior Specification

Important test classes:

- `MobileApiControllerInventoryTest`: mobile inventory and photo binding behavior.
- `QueueBoardServiceTest`: REAL/SHADOW queue entries, task promotion, holding queues, worker interruption.
- `RepairEstimateServiceTest`: estimate save, plan generation, movement tasks, repair lifecycle statuses.
- `RepairReworkServiceTest`: rework child processes and source-process acceptance.
- `PhotoProcessingQueueServiceTest`: READY deletes incoming object; FAILED keeps incoming object for retry.
- `RentalItemHardDeleteTest`: hard-delete cascade behavior.
- `RentalItemEventServiceTest`: history/photo/accessory snapshot rules.
- `WorkerManagementServiceTest`: worker validation and group membership rules.

Migration agents should port these behavioral contracts before changing UI workflows.

## Panel Development Tunnel

- The Vite development server explicitly allows the current CloudPub tunnel host
  `furiously-steadfast-crayfish.cloudpub.ru` so the React panel can be opened from
  a mobile device without disabling Vite host validation globally.

Evidence:

- `panel/vite.config.ts`
