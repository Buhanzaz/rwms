package dev.buhanzaz.rwms.assistant.fixture;

/** Safe fixture that uses constructor injection and a model owned by assistant-service. */
public final class SafeAssistantOwnedComponent {
  private final AssistantModel model;

  public SafeAssistantOwnedComponent(AssistantModel model) {
    this.model = model;
  }

  public AssistantModel model() {
    return model;
  }

  /** Minimal service-owned value used to prove that local assistant dependencies remain allowed. */
  public record AssistantModel(String id) {}
}
