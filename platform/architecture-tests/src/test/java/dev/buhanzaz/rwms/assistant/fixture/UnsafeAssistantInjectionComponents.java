package dev.buhanzaz.rwms.assistant.fixture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Groups negative fixtures for implicit Spring collaborator injection. */
public final class UnsafeAssistantInjectionComponents {
  private UnsafeAssistantInjectionComponents() {}

  /** Collaborator used only to make the injection style visible to ArchUnit. */
  public interface Collaborator {}

  /** Unsafe fixture that hides a required collaborator behind field injection. */
  @Component
  public static final class FieldInjectedComponent {
    @Autowired private Collaborator collaborator;
  }

  /** Unsafe fixture that permits a required collaborator to be replaced through method injection. */
  @Component
  public static final class MethodInjectedComponent {
    @Autowired
    void setCollaborator(Collaborator collaborator) {}
  }
}
