# assistant-service

Stateful rental assistant conversations. The service owns only its PostgreSQL
conversation, message, tool-call and event-inbox data; rental inquiries and
cabin availability remain owned by logistics-service.

The service is included in the repository root build. Validate it with:

    bash ./gradlew :services:assistant-service:test

The standalone settings file also keeps this command available:

    bash ./gradlew -p services/assistant-service test

For local launching, the ignored `.env.local` is imported automatically both
when Gradle is launched from this service directory and when the root build
launches the module. It is prefilled for the local assistant database at
`127.0.0.1:5442`, the panel at `http://localhost:8080`, and logistics at
`http://127.0.0.1:8090`. Add a real `LLM_API_KEY` locally before starting the
service: startup fails closed while it remains blank. Environment variables
override this local file in production. Local Kafka consumption is enabled so
completed rental inquiries archive their assistant conversation.
